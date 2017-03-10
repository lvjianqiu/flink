/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.test.checkpointing;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.testkit.JavaTestKit;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import java.io.FileNotFoundException;
import java.util.concurrent.CountDownLatch;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.CoreOptions;
import org.apache.flink.core.testutils.OneShotLatch;
import org.apache.flink.runtime.akka.AkkaUtils;
import org.apache.flink.runtime.checkpoint.SubtaskState;
import org.apache.flink.runtime.checkpoint.TaskState;
import org.apache.flink.runtime.checkpoint.savepoint.SavepointV1;
import org.apache.flink.runtime.client.JobExecutionException;
import org.apache.flink.runtime.deployment.TaskDeploymentDescriptor;
import org.apache.flink.runtime.executiongraph.TaskInformation;
import org.apache.flink.runtime.instance.ActorGateway;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.JobVertex;
import org.apache.flink.runtime.jobgraph.JobVertexID;
import org.apache.flink.runtime.jobgraph.SavepointRestoreSettings;
import org.apache.flink.runtime.messages.JobManagerMessages.CancelJob;
import org.apache.flink.runtime.messages.JobManagerMessages.DisposeSavepoint;
import org.apache.flink.runtime.messages.JobManagerMessages.TriggerSavepoint;
import org.apache.flink.runtime.messages.JobManagerMessages.TriggerSavepointSuccess;
import org.apache.flink.runtime.state.ChainedStateHandle;
import org.apache.flink.runtime.state.StreamStateHandle;
import org.apache.flink.runtime.state.filesystem.FileStateHandle;
import org.apache.flink.runtime.state.filesystem.FsStateBackend;
import org.apache.flink.runtime.state.filesystem.FsStateBackendFactory;
import org.apache.flink.runtime.testingUtils.TestingCluster;
import org.apache.flink.runtime.testingUtils.TestingJobManagerMessages.RequestSavepoint;
import org.apache.flink.runtime.testingUtils.TestingJobManagerMessages.ResponseSavepoint;
import org.apache.flink.runtime.testingUtils.TestingJobManagerMessages.WaitForAllVerticesToBeRunning;
import org.apache.flink.runtime.testingUtils.TestingTaskManagerMessages;
import org.apache.flink.runtime.testingUtils.TestingTaskManagerMessages.ResponseSubmitTaskListener;
import org.apache.flink.runtime.testutils.CommonTestUtils;
import org.apache.flink.streaming.api.checkpoint.ListCheckpointed;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.IterativeStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.DiscardingSink;
import org.apache.flink.streaming.api.functions.source.RichSourceFunction;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.streaming.api.graph.StreamGraph;
import org.apache.flink.util.Collector;
import org.apache.flink.util.TestLogger;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Deadline;
import scala.concurrent.duration.FiniteDuration;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.apache.flink.runtime.messages.JobManagerMessages.getDisposeSavepointSuccess;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Integration test for triggering and resuming from savepoints.
 */
public class SavepointITCase extends TestLogger {

	private static final Logger LOG = LoggerFactory.getLogger(SavepointITCase.class);

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	/**
	 * Triggers a savepoint for a job that uses the FsStateBackend. We expect
	 * that all checkpoint files are written to a new savepoint directory.
	 *
	 * <ol>
	 * <li>Submit job, wait for some progress</li>
	 * <li>Trigger savepoint and verify that savepoint has been created</li>
	 * <li>Shut down the cluster, re-submit the job from the savepoint,
	 * verify that the initial state has been reset, and
	 * all tasks are running again</li>
	 * <li>Cancel job, dispose the savepoint, and verify that everything
	 * has been cleaned up</li>
	 * </ol>
	 */
	@Test
	public void testTriggerSavepointAndResumeWithFileBasedCheckpoints() throws Exception {
		// Config
		final int numTaskManagers = 2;
		final int numSlotsPerTaskManager = 2;
		final int parallelism = numTaskManagers * numSlotsPerTaskManager;
		final Deadline deadline = new FiniteDuration(5, TimeUnit.MINUTES).fromNow();
		final File testRoot = folder.newFolder();

		TestingCluster flink = null;

		try {
			// Create a test actor system
			ActorSystem testActorSystem = AkkaUtils.createDefaultActorSystem();

			// Flink configuration
			final Configuration config = new Configuration();
			config.setInteger(ConfigConstants.LOCAL_NUMBER_TASK_MANAGER, numTaskManagers);
			config.setInteger(ConfigConstants.TASK_MANAGER_NUM_TASK_SLOTS, numSlotsPerTaskManager);

			final File checkpointDir = new File(testRoot, "checkpoints");
			final File savepointRootDir = new File(testRoot, "savepoints");

			if (!checkpointDir.mkdir() || !savepointRootDir.mkdirs()) {
				fail("Test setup failed: failed to create temporary directories.");
			}

			// Use file based checkpoints
			config.setString(CoreOptions.STATE_BACKEND, "filesystem");
			config.setString(FsStateBackendFactory.CHECKPOINT_DIRECTORY_URI_CONF_KEY, checkpointDir.toURI().toString());
			config.setString(FsStateBackendFactory.MEMORY_THRESHOLD_CONF_KEY, "0");
			config.setString(ConfigConstants.SAVEPOINT_DIRECTORY_KEY, savepointRootDir.toURI().toString());

			// Start Flink
			flink = new TestingCluster(config);
			flink.start(true);

			// Submit the job
			final JobGraph jobGraph = createJobGraph(parallelism, 0, 1000);
			final JobID jobId = jobGraph.getJobID();

			// Reset the static test job helpers
			StatefulCounter.resetForTest(parallelism);

			// Retrieve the job manager
			ActorGateway jobManager = Await.result(flink.leaderGateway().future(), deadline.timeLeft());

			LOG.info("Submitting job " + jobGraph.getJobID() + " in detached mode.");

			flink.submitJobDetached(jobGraph);

			LOG.info("Waiting for some progress.");

			// wait for the JobManager to be ready
			Future<Object> allRunning = jobManager.ask(new WaitForAllVerticesToBeRunning(jobId), deadline.timeLeft());
			Await.ready(allRunning, deadline.timeLeft());

			// wait for the Tasks to be ready
			StatefulCounter.getProgressLatch().await(deadline.timeLeft().toMillis(), TimeUnit.MILLISECONDS);

			LOG.info("Triggering a savepoint.");
			Future<Object> savepointPathFuture = jobManager.ask(new TriggerSavepoint(jobId, Option.<String>empty()), deadline.timeLeft());
			final String savepointPath = ((TriggerSavepointSuccess) Await.result(savepointPathFuture, deadline.timeLeft())).savepointPath();
			LOG.info("Retrieved savepoint path: " + savepointPath + ".");

			// Retrieve the savepoint from the testing job manager
			LOG.info("Requesting the savepoint.");
			Future<Object> savepointFuture = jobManager.ask(new RequestSavepoint(savepointPath), deadline.timeLeft());

			SavepointV1 savepoint = (SavepointV1) ((ResponseSavepoint) Await.result(savepointFuture, deadline.timeLeft())).savepoint();
			LOG.info("Retrieved savepoint: " + savepointPath + ".");

			// Shut down the Flink cluster (thereby canceling the job)
			LOG.info("Shutting down Flink cluster.");
			flink.shutdown();
			flink.awaitTermination();

			// - Verification START -------------------------------------------

			// Only one savepoint should exist
			File[] files = savepointRootDir.listFiles();

			if (files != null) {
				assertEquals("Savepoint not created in expected directory", 1, files.length);
				assertTrue("Savepoint did not create self-contained directory", files[0].isDirectory());

				File savepointDir = files[0];
				File[] savepointFiles = savepointDir.listFiles();
				assertNotNull(savepointFiles);

				// Expect one metadata file and one checkpoint file per stateful
				// parallel subtask
				String errMsg = "Did not write expected number of savepoint/checkpoint files to directory: "
					+ Arrays.toString(savepointFiles);
				assertEquals(errMsg, 1 + parallelism, savepointFiles.length);
			} else {
				fail("Savepoint not created in expected directory");
			}

			// We currently have the following directory layout: checkpointDir/jobId/chk-ID
			File jobCheckpoints = new File(checkpointDir, jobId.toString());

			if (jobCheckpoints.exists()) {
				files = jobCheckpoints.listFiles();
				assertNotNull("Checkpoint directory empty", files);
				assertEquals("Checkpoints directory not clean: " + Arrays.toString(files), 0, files.length);
			}

			// - Verification END ---------------------------------------------

			// Restart the cluster
			LOG.info("Restarting Flink cluster.");
			flink.start();

			// Retrieve the job manager
			LOG.info("Retrieving JobManager.");
			jobManager = Await.result(flink.leaderGateway().future(), deadline.timeLeft());
			LOG.info("JobManager: " + jobManager + ".");

			// Reset static test helpers
			StatefulCounter.resetForTest(parallelism);

			// Gather all task deployment descriptors
			final Throwable[] error = new Throwable[1];
			final TestingCluster finalFlink = flink;
			final Multimap<JobVertexID, TaskDeploymentDescriptor> tdds = HashMultimap.create();

			new JavaTestKit(testActorSystem) {{

				new Within(deadline.timeLeft()) {
					@Override
					protected void run() {
						try {
							// Register to all submit task messages for job
							for (ActorRef taskManager : finalFlink.getTaskManagersAsJava()) {
								taskManager.tell(new TestingTaskManagerMessages
									.RegisterSubmitTaskListener(jobId), getTestActor());
							}

							// Set the savepoint path
							jobGraph.setSavepointRestoreSettings(SavepointRestoreSettings.forPath(savepointPath));

							LOG.info("Resubmitting job " + jobGraph.getJobID() + " with " +
								"savepoint path " + savepointPath + " in detached mode.");

							// Submit the job
							finalFlink.submitJobDetached(jobGraph);

							int numTasks = 0;
							for (JobVertex jobVertex : jobGraph.getVertices()) {
								numTasks += jobVertex.getParallelism();
							}

							// Gather the task deployment descriptors
							LOG.info("Gathering " + numTasks + " submitted " +
								"TaskDeploymentDescriptor instances.");

							for (int i = 0; i < numTasks; i++) {
								ResponseSubmitTaskListener resp = (ResponseSubmitTaskListener)
									expectMsgAnyClassOf(getRemainingTime(),
										ResponseSubmitTaskListener.class);

								TaskDeploymentDescriptor tdd = resp.tdd();

								LOG.info("Received: " + tdd.toString() + ".");

								TaskInformation taskInformation = tdd
									.getSerializedTaskInformation()
									.deserializeValue(getClass().getClassLoader());

								tdds.put(taskInformation.getJobVertexId(), tdd);
							}
						} catch (Throwable t) {
							error[0] = t;
						}
					}
				};
			}};

			// - Verification START -------------------------------------------

			String errMsg = "Error during gathering of TaskDeploymentDescriptors";
			assertNull(errMsg, error[0]);

			// Verify that all tasks, which are part of the savepoint
			// have a matching task deployment descriptor.
			for (TaskState taskState : savepoint.getTaskStates()) {
				Collection<TaskDeploymentDescriptor> taskTdds = tdds.get(taskState.getJobVertexID());

				errMsg = "Missing task for savepoint state for operator "
					+ taskState.getJobVertexID() + ".";
				assertTrue(errMsg, taskTdds.size() > 0);

				assertEquals(taskState.getNumberCollectedStates(), taskTdds.size());

				for (TaskDeploymentDescriptor tdd : taskTdds) {
					SubtaskState subtaskState = taskState.getState(tdd.getSubtaskIndex());

					assertNotNull(subtaskState);

					errMsg = "Initial operator state mismatch.";
					assertEquals(errMsg, subtaskState.getLegacyOperatorState(),
						tdd.getTaskStateHandles().getLegacyOperatorState());
				}
			}

			// Await state is restored
			StatefulCounter.getRestoreLatch().await(deadline.timeLeft().toMillis(), TimeUnit.MILLISECONDS);

			// Await some progress after restore
			StatefulCounter.getProgressLatch().await(deadline.timeLeft().toMillis(), TimeUnit.MILLISECONDS);

			// - Verification END ---------------------------------------------

			LOG.info("Cancelling job " + jobId + ".");
			jobManager.tell(new CancelJob(jobId));

			LOG.info("Disposing savepoint " + savepointPath + ".");
			Future<Object> disposeFuture = jobManager.ask(new DisposeSavepoint(savepointPath), deadline.timeLeft());

			errMsg = "Failed to dispose savepoint " + savepointPath + ".";
			Object resp = Await.result(disposeFuture, deadline.timeLeft());
			assertTrue(errMsg, resp.getClass() == getDisposeSavepointSuccess().getClass());

			// - Verification START -------------------------------------------

			// The checkpoint files
			List<File> checkpointFiles = new ArrayList<>();

			for (TaskState stateForTaskGroup : savepoint.getTaskStates()) {
				for (SubtaskState subtaskState : stateForTaskGroup.getStates()) {
					ChainedStateHandle<StreamStateHandle> streamTaskState = subtaskState.getLegacyOperatorState();

					for (int i = 0; i < streamTaskState.getLength(); i++) {
						if (streamTaskState.get(i) != null) {
							FileStateHandle fileStateHandle = (FileStateHandle) streamTaskState.get(i);
							checkpointFiles.add(new File(fileStateHandle.getFilePath().toUri()));
						}
					}
				}
			}

			// The checkpoint files of the savepoint should have been discarded
			for (File f : checkpointFiles) {
				errMsg = "Checkpoint file " + f + " not cleaned up properly.";
				assertFalse(errMsg, f.exists());
			}

			if (checkpointFiles.size() > 0) {
				File parent = checkpointFiles.get(0).getParentFile();
				errMsg = "Checkpoint parent directory " + parent + " not cleaned up properly.";
				assertFalse(errMsg, parent.exists());
			}

			// All savepoints should have been cleaned up
			errMsg = "Savepoints directory not cleaned up properly: " +
				Arrays.toString(savepointRootDir.listFiles()) + ".";
			assertEquals(errMsg, 0, savepointRootDir.listFiles().length);

			// - Verification END ---------------------------------------------
		} finally {
			if (flink != null) {
				flink.shutdown();
			}
		}
	}

	@Test
	public void testSubmitWithUnknownSavepointPath() throws Exception {
		// Config
		int numTaskManagers = 1;
		int numSlotsPerTaskManager = 1;
		int parallelism = numTaskManagers * numSlotsPerTaskManager;

		// Test deadline
		final Deadline deadline = new FiniteDuration(5, TimeUnit.MINUTES).fromNow();

		final File tmpDir = CommonTestUtils.createTempDirectory();
		final File savepointDir = new File(tmpDir, "savepoints");

		TestingCluster flink = null;

		try {
			// Flink configuration
			final Configuration config = new Configuration();
			config.setInteger(ConfigConstants.LOCAL_NUMBER_TASK_MANAGER, numTaskManagers);
			config.setInteger(ConfigConstants.TASK_MANAGER_NUM_TASK_SLOTS, numSlotsPerTaskManager);
			config.setString(ConfigConstants.SAVEPOINT_DIRECTORY_KEY,
				savepointDir.toURI().toString());

			LOG.info("Flink configuration: " + config + ".");

			// Start Flink
			flink = new TestingCluster(config);
			LOG.info("Starting Flink cluster.");
			flink.start();

			// Retrieve the job manager
			LOG.info("Retrieving JobManager.");
			ActorGateway jobManager = Await.result(
				flink.leaderGateway().future(),
				deadline.timeLeft());
			LOG.info("JobManager: " + jobManager + ".");

			// High value to ensure timeouts if restarted.
			int numberOfRetries = 1000;
			// Submit the job
			// Long delay to ensure that the test times out if the job
			// manager tries to restart the job.
			final JobGraph jobGraph = createJobGraph(parallelism, numberOfRetries, 3600000);

			// Set non-existing savepoint path
			jobGraph.setSavepointRestoreSettings(SavepointRestoreSettings.forPath("unknown path"));
			assertEquals("unknown path", jobGraph.getSavepointRestoreSettings().getRestorePath());

			LOG.info("Submitting job " + jobGraph.getJobID() + " in detached mode.");

			try {
				flink.submitJobAndWait(jobGraph, false);
			} catch (Exception e) {
				assertEquals(JobExecutionException.class, e.getClass());
				assertEquals(FileNotFoundException.class, e.getCause().getClass());
			}
		} finally {
			if (flink != null) {
				flink.shutdown();
			}
		}
	}

	// ------------------------------------------------------------------------
	// Test program
	// ------------------------------------------------------------------------

	/**
	 * Creates a streaming JobGraph from the StreamEnvironment.
	 */
	private JobGraph createJobGraph(
		int parallelism,
		int numberOfRetries,
		long restartDelay) {

		StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
		env.setParallelism(parallelism);
		env.disableOperatorChaining();
		env.getConfig().setRestartStrategy(RestartStrategies.fixedDelayRestart(numberOfRetries, restartDelay));
		env.getConfig().disableSysoutLogging();

		DataStream<Integer> stream = env
			.addSource(new InfiniteTestSource())
			.shuffle()
			.map(new StatefulCounter());

		stream.addSink(new DiscardingSink<Integer>());

		return env.getStreamGraph().getJobGraph();
	}

	private static class InfiniteTestSource implements SourceFunction<Integer> {

		private static final long serialVersionUID = 1L;
		private volatile boolean running = true;

		@Override
		public void run(SourceContext<Integer> ctx) throws Exception {
			while (running) {
				synchronized (ctx.getCheckpointLock()) {
					ctx.collect(1);
				}
			}
		}

		@Override
		public void cancel() {
			running = false;
		}
	}

	private static class StatefulCounter extends RichMapFunction<Integer, Integer> implements ListCheckpointed<byte[]>{

		private static volatile CountDownLatch progressLatch = new CountDownLatch(0);
		private static volatile CountDownLatch restoreLatch = new CountDownLatch(0);

		private int numCollectedElements = 0;

		private static final long serialVersionUID = 7317800376639115920L;
		private byte[] data;

		@Override
		public void open(Configuration parameters) throws Exception {
			if (data == null) {
				// We need this to be large, because we want to test with files
				Random rand = new Random(getRuntimeContext().getIndexOfThisSubtask());
				data = new byte[FsStateBackend.DEFAULT_FILE_STATE_THRESHOLD + 1];
				rand.nextBytes(data);
			}
		}

		@Override
		public Integer map(Integer value) throws Exception {
			for (int i = 0; i < data.length; i++) {
				data[i] += 1;
			}

			if (numCollectedElements++ > 10) {
				progressLatch.countDown();
			}

			return value;
		}

		@Override
		public List<byte[]> snapshotState(long checkpointId, long timestamp) throws Exception {
			return Collections.singletonList(data);
		}

		@Override
		public void restoreState(List<byte[]> state) throws Exception {
			if (state.isEmpty() || state.size() > 1) {
				throw new RuntimeException("Test failed due to unexpected recovered state size " + state.size());
			}
			this.data = state.get(0);

			restoreLatch.countDown();
		}

		// --------------------------------------------------------------------

		static CountDownLatch getProgressLatch() {
			return progressLatch;
		}

		static CountDownLatch getRestoreLatch() {
			return restoreLatch;
		}

		static void resetForTest(int parallelism) {
			progressLatch = new CountDownLatch(parallelism);
			restoreLatch = new CountDownLatch(parallelism);
		}
	}

	private static final int ITER_TEST_PARALLELISM = 1;
	private static OneShotLatch[] ITER_TEST_SNAPSHOT_WAIT = new OneShotLatch[ITER_TEST_PARALLELISM];
	private static OneShotLatch[] ITER_TEST_RESTORE_WAIT = new OneShotLatch[ITER_TEST_PARALLELISM];
	private static int[] ITER_TEST_CHECKPOINT_VERIFY = new int[ITER_TEST_PARALLELISM];

	@Test
	public void testSavepointForJobWithIteration() throws Exception {

		for (int i = 0; i < ITER_TEST_PARALLELISM; ++i) {
			ITER_TEST_SNAPSHOT_WAIT[i] = new OneShotLatch();
			ITER_TEST_RESTORE_WAIT[i] = new OneShotLatch();
			ITER_TEST_CHECKPOINT_VERIFY[i] = 0;
		}

		TemporaryFolder folder = new TemporaryFolder();
		folder.create();
		// Temporary directory for file state backend
		final File tmpDir = folder.newFolder();

		final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
		final IntegerStreamSource source = new IntegerStreamSource();
		IterativeStream<Integer> iteration = env.addSource(source)
				.flatMap(new RichFlatMapFunction<Integer, Integer>() {

					private static final long serialVersionUID = 1L;

					@Override
					public void flatMap(Integer in, Collector<Integer> clctr) throws Exception {
						clctr.collect(in);
					}
				}).setParallelism(ITER_TEST_PARALLELISM)
				.keyBy(new KeySelector<Integer, Object>() {

					private static final long serialVersionUID = 1L;

					@Override
					public Object getKey(Integer value) throws Exception {
						return value;
					}
				})
				.flatMap(new DuplicateFilter())
				.setParallelism(ITER_TEST_PARALLELISM)
				.iterate();

		DataStream<Integer> iterationBody = iteration
				.map(new MapFunction<Integer, Integer>() {
					private static final long serialVersionUID = 1L;

					@Override
					public Integer map(Integer value) throws Exception {
						return value;
					}
				})
				.setParallelism(ITER_TEST_PARALLELISM);

		iteration.closeWith(iterationBody);

		StreamGraph streamGraph = env.getStreamGraph();
		streamGraph.setJobName("Test");

		JobGraph jobGraph = streamGraph.getJobGraph();

		Configuration config = new Configuration();
		config.addAll(jobGraph.getJobConfiguration());
		config.setLong(ConfigConstants.TASK_MANAGER_MEMORY_SIZE_KEY, -1L);
		config.setInteger(ConfigConstants.TASK_MANAGER_NUM_TASK_SLOTS, 2 * jobGraph.getMaximumParallelism());
		final File checkpointDir = new File(tmpDir, "checkpoints");
		final File savepointDir = new File(tmpDir, "savepoints");

		if (!checkpointDir.mkdir() || !savepointDir.mkdirs()) {
			fail("Test setup failed: failed to create temporary directories.");
		}

		config.setString(CoreOptions.STATE_BACKEND, "filesystem");
		config.setString(FsStateBackendFactory.CHECKPOINT_DIRECTORY_URI_CONF_KEY,
				checkpointDir.toURI().toString());
		config.setString(FsStateBackendFactory.MEMORY_THRESHOLD_CONF_KEY, "0");
		config.setString(ConfigConstants.SAVEPOINT_DIRECTORY_KEY,
				savepointDir.toURI().toString());

		TestingCluster cluster = new TestingCluster(config, false);
		String savepointPath = null;
		try {
			cluster.start();

			cluster.submitJobDetached(jobGraph);
			for (OneShotLatch latch : ITER_TEST_SNAPSHOT_WAIT) {
				latch.await();
			}
			savepointPath = cluster.triggerSavepoint(jobGraph.getJobID());
			source.cancel();

			jobGraph = streamGraph.getJobGraph();
			jobGraph.setSavepointRestoreSettings(SavepointRestoreSettings.forPath(savepointPath));

			cluster.submitJobDetached(jobGraph);
			for (OneShotLatch latch : ITER_TEST_RESTORE_WAIT) {
				latch.await();
			}
			source.cancel();
		} finally {
			if (null != savepointPath) {
				cluster.disposeSavepoint(savepointPath);
			}
			cluster.stop();
			cluster.awaitTermination();
		}
	}

	private static final class IntegerStreamSource
			extends RichSourceFunction<Integer>
			implements ListCheckpointed<Integer> {

		private static final long serialVersionUID = 1L;
		private volatile boolean running;
		private volatile boolean isRestored;
		private int emittedCount;

		public IntegerStreamSource() {
			this.running = true;
			this.isRestored = false;
			this.emittedCount = 0;
		}

		@Override
		public void run(SourceContext<Integer> ctx) throws Exception {

			while (running) {
				synchronized (ctx.getCheckpointLock()) {
					ctx.collect(emittedCount);
				}

				if (emittedCount < 100) {
					++emittedCount;
				} else {
					emittedCount = 0;
				}
				Thread.sleep(1);
			}
		}

		@Override
		public void cancel() {
			running = false;
		}

		@Override
		public List<Integer> snapshotState(long checkpointId, long timestamp) throws Exception {
			ITER_TEST_CHECKPOINT_VERIFY[getRuntimeContext().getIndexOfThisSubtask()] = emittedCount;
			return Collections.singletonList(emittedCount);
		}

		@Override
		public void restoreState(List<Integer> state) throws Exception {
			if (!state.isEmpty()) {
				this.emittedCount = state.get(0);
			}
			Assert.assertEquals(ITER_TEST_CHECKPOINT_VERIFY[getRuntimeContext().getIndexOfThisSubtask()], emittedCount);
			ITER_TEST_RESTORE_WAIT[getRuntimeContext().getIndexOfThisSubtask()].trigger();
		}
	}

	public static class DuplicateFilter extends RichFlatMapFunction<Integer, Integer> {

		static final ValueStateDescriptor<Boolean> descriptor = new ValueStateDescriptor<>("seen", Boolean.class, false);
		private static final long serialVersionUID = 1L;
		private ValueState<Boolean> operatorState;

		@Override
		public void open(Configuration configuration) {
			operatorState = this.getRuntimeContext().getState(descriptor);
		}

		@Override
		public void flatMap(Integer value, Collector<Integer> out) throws Exception {
			if (!operatorState.value()) {
				out.collect(value);
				operatorState.update(true);
			}

			if (30 == value) {
				ITER_TEST_SNAPSHOT_WAIT[getRuntimeContext().getIndexOfThisSubtask()].trigger();
			}
		}
	}
}
