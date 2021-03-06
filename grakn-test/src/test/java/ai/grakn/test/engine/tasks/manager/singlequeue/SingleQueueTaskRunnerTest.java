/*
 * Grakn - A Distributed Semantic Database
 * Copyright (C) 2016  Grakn Labs Limited
 *
 * Grakn is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Grakn is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Grakn. If not, see <http://www.gnu.org/licenses/gpl.txt>.
 *
 */

package ai.grakn.test.engine.tasks.manager.singlequeue;

import ai.grakn.engine.TaskId;
import ai.grakn.engine.tasks.ExternalOffsetStorage;
import ai.grakn.engine.tasks.TaskConfiguration;
import ai.grakn.engine.tasks.TaskState;
import ai.grakn.engine.tasks.manager.singlequeue.SingleQueueTaskManager;
import ai.grakn.engine.tasks.manager.singlequeue.SingleQueueTaskRunner;
import ai.grakn.engine.tasks.mock.EndlessExecutionMockTask;
import ai.grakn.engine.tasks.mock.LongExecutionMockTask;
import ai.grakn.engine.tasks.mock.ShortExecutionMockTask;
import ai.grakn.engine.tasks.storage.TaskStateInMemoryStore;
import ai.grakn.engine.util.EngineID;
import ai.grakn.test.EngineContext;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultiset;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multiset;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.grakn.engine.TaskStatus.COMPLETED;
import static ai.grakn.engine.TaskStatus.FAILED;
import static ai.grakn.engine.TaskStatus.RUNNING;
import static ai.grakn.engine.TaskStatus.STOPPED;
import static ai.grakn.engine.tasks.TaskSchedule.at;
import static ai.grakn.engine.tasks.mock.MockBackgroundTask.cancelledTasks;
import static ai.grakn.engine.tasks.mock.MockBackgroundTask.clearTasks;
import static ai.grakn.engine.tasks.mock.MockBackgroundTask.completedTasks;
import static ai.grakn.engine.tasks.mock.MockBackgroundTask.whenTaskFinishes;
import static ai.grakn.engine.tasks.mock.MockBackgroundTask.whenTaskStarts;
import static ai.grakn.test.engine.tasks.BackgroundTaskTestUtils.completableTasks;
import static ai.grakn.test.engine.tasks.BackgroundTaskTestUtils.configuration;
import static ai.grakn.test.engine.tasks.BackgroundTaskTestUtils.createTask;
import static ai.grakn.test.engine.tasks.BackgroundTaskTestUtils.failingTasks;
import static java.time.Duration.between;
import static java.time.Duration.ofMillis;
import static java.time.Instant.now;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@RunWith(JUnitQuickcheck.class)
public class SingleQueueTaskRunnerTest {

    @ClassRule
    public static final EngineContext zookeeperRunning = EngineContext.startKafkaServer();

    private static final EngineID engineID = EngineID.me();
    private static final int TIME_UNTIL_BACKOFF = 10;

    private SingleQueueTaskRunner taskRunner;
    private TaskStateInMemoryStore storage;
    private SingleQueueTaskManager mockedTM;
    private ExternalOffsetStorage offsetStorage;

    private MockGraknConsumer<TaskState, TaskConfiguration> lowPriorityConsumer;
    private TopicPartition lowPTopicPartition;

    @Before
    public void setUp() {
        clearTasks();

        storage = new TaskStateInMemoryStore();
        offsetStorage = mock(ExternalOffsetStorage.class);
        lowPriorityConsumer = new MockGraknConsumer<>(OffsetResetStrategy.EARLIEST);

        lowPTopicPartition = new TopicPartition("low-priority", 0);

        lowPriorityConsumer.assign(ImmutableSet.of(lowPTopicPartition));
        lowPriorityConsumer.updateBeginningOffsets(ImmutableMap.of(lowPTopicPartition, 0L));
        lowPriorityConsumer.updateEndOffsets(ImmutableMap.of(lowPTopicPartition, 0L));

        mockedTM = mock(SingleQueueTaskManager.class);
        when(mockedTM.storage()).thenReturn(storage);
        doAnswer(invocation -> {
            addTask(invocation.getArgument(0));
            return null;
        }).when(mockedTM).addTask(Mockito.any(), Mockito.any());
    }

    public void setUpTasks(List<List<TaskState>> tasks) {
        taskRunner = new SingleQueueTaskRunner(mockedTM, engineID, zookeeperRunning.config(), null, null, offsetStorage, lowPriorityConsumer);

        for (List<TaskState> taskList : tasks) {
            lowPriorityConsumer.schedulePollTask(() -> taskList.forEach(this::addTask));
        }

        lowPriorityConsumer.scheduleEmptyPollTask(() -> {
            Thread closeTaskRunner = new Thread(() -> {
                try {
                    taskRunner.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            closeTaskRunner.start();
        });
    }

    public static List<TaskState> tasks(List<? extends List<TaskState>> tasks) {
        return tasks.stream().flatMap(Collection::stream).collect(toList());
    }

    private void addTask(TaskState task) {
        Long offset = lowPriorityConsumer.endOffsets(ImmutableSet.of(lowPTopicPartition)).get(lowPTopicPartition);
        lowPriorityConsumer.addRecord(new ConsumerRecord<>(lowPTopicPartition.topic(), lowPTopicPartition.partition(), offset, task.copy(), configuration(task)));
        lowPriorityConsumer.updateEndOffsets(ImmutableMap.of(lowPTopicPartition, offset + 1));
    }

    @Property(trials=10)
    public void afterRunning_AllTasksAreAddedToStorage(List<List<TaskState>> tasks) throws Exception {
        setUpTasks(tasks);

        taskRunner.run();

        tasks(tasks).forEach(task ->
                assertNotNull(storage.getState(task.getId()))
        );
    }

    @Property(trials=10)
    public void afterRunning_AllNonFailingTasksAreRecordedAsCompleted(List<List<TaskState>> tasks) throws Exception {
        setUpTasks(tasks);

        taskRunner.run();

        completableTasks(tasks(tasks)).forEach(task ->
                assertThat("Task " + task + " should have completed.", storage.getState(task).status(), is(COMPLETED))
        );
    }

    @Property(trials=10)
    public void afterRunning_AllFailingTasksAreRecordedAsFailed(List<List<TaskState>> tasks) throws Exception {
        setUpTasks(tasks);

        taskRunner.run();

        failingTasks(tasks(tasks)).forEach(task ->
                assertThat("Task " + task + " should have failed.", storage.getState(task).status(), is(FAILED))
        );
    }

    @Property(trials=10)
    public void afterRunning_AllFailingTasksAreRecordedWithException(List<List<TaskState>> tasks) throws Exception {
        setUpTasks(tasks);

        taskRunner.run();

        failingTasks(tasks(tasks)).forEach(task ->
                assertThat("Task " + task + " should have stack trace.", storage.getState(task).stackTrace(), notNullValue())
        );
    }

    @Property(trials=10)
    public void afterRunning_AllNonFailingTasksHaveCompletedExactlyOnce(List<List<TaskState>> tasks) throws Exception {
        setUpTasks(tasks);

        taskRunner.run();

        Multiset<TaskId> expectedCompletedTasks = ImmutableMultiset.copyOf(completableTasks(tasks(tasks)));

        assertEquals(expectedCompletedTasks, completedTasks());
    }

    @Property(trials=10)
    public void whenRunning_EngineIdIsNonNull(List<List<TaskState>> tasks) throws Exception {
        assumeThat(tasks.size(), greaterThan(0));
        assumeThat(tasks.get(0).size(), greaterThan(0));

        storage = spy(storage);

        doCallRealMethod().when(storage).updateState(argThat(argument -> {
            if (argument.status() == FAILED || argument.status() == COMPLETED){
                assertNull(argument.engineID());
            } else if(argument.status() == RUNNING){
                assertNotNull(argument.engineID());
            }
            return true;
        }));

        setUpTasks(tasks);
        taskRunner.run();
    }

    @Test
    public void whenRunIsCalled_DontReturnUntilCloseIsCalled() throws Exception {
        setUpTasks(ImmutableList.of());

        Thread thread = new Thread(taskRunner);
        thread.start();

        assertTrue(thread.isAlive());

        taskRunner.close();
        thread.join();

        assertFalse(thread.isAlive());
    }

    @Test
    public void whenATaskIsStoppedBeforeExecution_TheTaskIsNotCancelled() throws Exception {
        TaskState task = createTask();

        setUpTasks(ImmutableList.of(ImmutableList.of(task)));

        taskRunner.stopTask(task.getId());

        taskRunner.run();

        assertThat(cancelledTasks(), empty());
        assertThat(completedTasks(), contains(task.getId()));
    }

    @Test
    public void whenATaskIsStoppedBeforeExecution_TheTaskIsMarkedAsCompleted() throws Exception {
        TaskState task = createTask();

        setUpTasks(ImmutableList.of(ImmutableList.of(task)));

        taskRunner.stopTask(task.getId());

        taskRunner.run();

        assertThat(storage.getState(task.getId()).status(), is(COMPLETED));
    }

    @Test
    public void whenATaskIsStoppedBeforeExecution_ReturnFalse() throws Exception {
        TaskState task = createTask();

        setUpTasks(ImmutableList.of(ImmutableList.of(task)));

        boolean stopped = taskRunner.stopTask(task.getId());

        assertFalse(stopped);
    }

    @Test
    public void whenATaskIsStoppedDuringExecution_TheTaskIsCancelled() throws Exception {
        TaskState task = createTask(EndlessExecutionMockTask.class);

        setUpTasks(ImmutableList.of(ImmutableList.of(task)));

        whenTaskStarts(taskRunner::stopTask);

        taskRunner.run();

        assertThat(cancelledTasks(), contains(task.getId()));
    }

    @Test
    public void whenATaskIsStoppedDuringExecution_TheTaskIsMarkedAsStopped() throws Exception {
        TaskState task = createTask(EndlessExecutionMockTask.class);

        setUpTasks(ImmutableList.of(ImmutableList.of(task)));

        whenTaskStarts(taskRunner::stopTask);

        taskRunner.run();

        assertThat(storage.getState(task.getId()).status(), is(STOPPED));
    }

    @Test
    public void whenATaskIsStoppedDuringExecution_ReturnTrue() throws Exception {
        TaskState task = createTask(EndlessExecutionMockTask.class);

        setUpTasks(ImmutableList.of(ImmutableList.of(task)));

        final Boolean[] stopped = {null};

        whenTaskStarts(taskId ->
            stopped[0] = taskRunner.stopTask(taskId)
        );

        taskRunner.run();

        assertTrue(stopped[0]);
    }

    @Test
    public void whenATaskIsStoppedAfterExecution_TheTaskIsCompleted() throws Exception {
        TaskState task = createTask(LongExecutionMockTask.class);

        setUpTasks(ImmutableList.of(ImmutableList.of(task)));

        whenTaskFinishes(taskRunner::stopTask);

        taskRunner.run();

        assertThat(cancelledTasks(), empty());
        assertThat(completedTasks(), contains(task.getId()));
    }

    @Test
    public void whenATaskIsStoppedAfterExecution_TheTaskIsMarkedAsCompleted() throws Exception {
        TaskState task = createTask(LongExecutionMockTask.class);

        setUpTasks(ImmutableList.of(ImmutableList.of(task)));

        whenTaskFinishes(taskRunner::stopTask);

        taskRunner.run();

        assertThat(storage.getState(task.getId()).status(), is(COMPLETED));
    }

    @Test
    public void whenATaskIsStoppedAfterExecution_ReturnFalse() throws Exception {
        TaskState task = createTask();

        setUpTasks(ImmutableList.of(ImmutableList.of(task)));

        taskRunner.run();

        boolean stopped = taskRunner.stopTask(task.getId());

        assertFalse(stopped);
    }

    @Test
    public void whenATaskIsMarkedAsStoppedInStorage_ItIsNotExecuted() throws Exception {
        TaskState task = createTask();

        setUpTasks(ImmutableList.of(ImmutableList.of(task)));

        storage.newState(task.markStopped());

        taskRunner.run();

        assertThat(completedTasks(), empty());
    }

    @Test
    public void whenATaskIsStoppedDifferentToTheOneRunning_DoNotStopTheRunningTask() {
        TaskState task1 = createTask();
        TaskState task2 = createTask();

        setUpTasks(ImmutableList.of(ImmutableList.of(task1)));

        whenTaskStarts(taskId -> taskRunner.stopTask(task2.getId()));

        taskRunner.run();

        assertThat(storage.getState(task1.getId()).status(), is(COMPLETED));
    }

    @Test
    public void whenDelayedTaskIsExecuted_ItIsOnlyExecutedAfterDelay() {
        final Duration delay = ofMillis(1000);
        final Instant submittedTime = now();
        final Instant[] startedTime = {null};
        whenTaskStarts(taskId -> {
            // Sleep so that the delay is not equal to duration in fast environments
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            startedTime[0] = now();
        });

        TaskState delayedTask = createTask(ShortExecutionMockTask.class, at(submittedTime.plus(delay)));
        setUpTasks(ImmutableList.of(ImmutableList.of(delayedTask)));

        taskRunner.run();

        Duration duration = between(submittedTime, startedTime[0]);
        assertThat(storage.getState(delayedTask.getId()).status(), is(COMPLETED));
        assertThat(duration, greaterThan(delay));
    }

    @Test
    public void whenNonDelayedTaskIsExecuted_ItIsExecutedImmediately(){
        final Duration delay = ofMillis(1000);
        final Instant submittedTime = now();
        final Map<TaskId, Instant> startedTime = new HashMap<>();
        whenTaskStarts(taskId ->
                startedTime.put(taskId, now())
        );

        TaskState delayedTask = createTask(ShortExecutionMockTask.class, at(submittedTime.plus(delay)));
        TaskState instantTask = createTask(ShortExecutionMockTask.class, at(submittedTime));
        setUpTasks(ImmutableList.of(ImmutableList.of(delayedTask, instantTask)));

        taskRunner.run();

        assertThat(storage.getState(delayedTask.getId()).status(), is(COMPLETED));
        assertThat(storage.getState(instantTask.getId()).status(), is(COMPLETED));

        assertThat(startedTime.get(instantTask.getId()), lessThan(startedTime.get(delayedTask.getId())));
    }
}