package com.hubspot.singularity.scheduler;

import java.text.ParseException;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.apache.mesos.Protos.TaskState;
import org.quartz.CronExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.hubspot.mesos.MesosUtils;
import com.hubspot.singularity.SingularityPendingRequestId;
import com.hubspot.singularity.SingularityPendingRequestId.PendingType;
import com.hubspot.singularity.SingularityPendingTaskId;
import com.hubspot.singularity.SingularityRack;
import com.hubspot.singularity.SingularityRequest;
import com.hubspot.singularity.SingularityRequestCleanup;
import com.hubspot.singularity.SingularityRequestCleanup.RequestCleanupType;
import com.hubspot.singularity.SingularityRequestHistory;
import com.hubspot.singularity.SingularitySlave;
import com.hubspot.singularity.SingularityTask;
import com.hubspot.singularity.SingularityTaskCleanup;
import com.hubspot.singularity.SingularityTaskCleanup.TaskCleanupType;
import com.hubspot.singularity.SingularityTaskId;
import com.hubspot.singularity.SingularityTaskIdHistory;
import com.hubspot.singularity.SingularityTaskRequest;
import com.hubspot.singularity.data.RackManager;
import com.hubspot.singularity.data.RequestManager;
import com.hubspot.singularity.data.SlaveManager;
import com.hubspot.singularity.data.TaskManager;
import com.hubspot.singularity.data.history.HistoryManager;
import com.hubspot.singularity.data.history.HistoryManager.OrderDirection;
import com.hubspot.singularity.data.history.HistoryManager.RequestHistoryOrderBy;
import com.hubspot.singularity.data.history.HistoryManager.TaskHistoryOrderBy;
import com.hubspot.singularity.smtp.SingularityMailer;

public class SingularityScheduler extends SingularitySchedulerBase {

  private final static Logger LOG = LoggerFactory.getLogger(SingularityScheduler.class);
  
  private final TaskManager taskManager;
  private final RequestManager requestManager;
  
  private final SlaveManager slaveManager;
  private final RackManager rackManager;
  
  private final HistoryManager historyManager;
  private final SingularityMailer mailer;
  
  @Inject
  public SingularityScheduler(TaskManager taskManager, RequestManager requestManager, SlaveManager slaveManager, RackManager rackManager, HistoryManager historyManager, SingularityMailer mailer) {
    this.taskManager = taskManager;
    this.requestManager = requestManager;
    this.slaveManager = slaveManager;
    this.rackManager = rackManager;
    this.historyManager = historyManager;
    this.mailer = mailer;
  }
  
  private void checkTaskForDecomissionCleanup(final Set<String> requestIdsToReschedule, final Set<SingularityTaskId> matchingTaskIds, SingularityTask task, String decomissioningObject) {
    requestIdsToReschedule.add(task.getTaskRequest().getRequest().getId());
    
    matchingTaskIds.add(task.getTaskId());

    if (!task.getTaskRequest().getRequest().isScheduled()) {
      LOG.trace(String.format("Scheduling a cleanup task for %s due to decomissioning %s", task.getTaskId(), decomissioningObject));
      
      taskManager.createCleanupTask(new SingularityTaskCleanup(Optional.<String> absent(), TaskCleanupType.DECOMISSIONING, System.currentTimeMillis(), task.getTaskId().getId(), task.getTaskRequest().getRequest().getId()));
    } else {
      LOG.trace(String.format("Not adding scheduled task %s to cleanup queue", task.getTaskId()));
    }
  }
  
  public void checkForDecomissions(List<SingularityTaskId> activeTaskIds) {
    final long start = System.currentTimeMillis();
    
    final Set<String> requestIdsToReschedule = Sets.newHashSet();
    final Set<SingularityTaskId> matchingTaskIds = Sets.newHashSet();
    
    final List<SingularitySlave> slaves = slaveManager.getDecomissioningObjectsFiltered();
    
    for (SingularitySlave slave : slaves) {
      for (SingularityTaskId activeTaskId : activeTaskIds) {
        if (activeTaskId.getHost().equals(slave.getHost())) {
          Optional<SingularityTask> maybeTask = taskManager.getActiveTask(activeTaskId.getId());
          if (slave.getId().equals(maybeTask.get().getOffer().getSlaveId().getValue())) {
            checkTaskForDecomissionCleanup(requestIdsToReschedule, matchingTaskIds, maybeTask.get(), slave.toString());
          }
        }
      }
    }
    
    final List<SingularityRack> racks = rackManager.getDecomissioningObjectsFiltered();
    
    for (SingularityRack rack : racks) {
      for (SingularityTaskId activeTaskId : activeTaskIds) {
        if (matchingTaskIds.contains(activeTaskId)) {
          continue;
        }
    
        if (rack.getId().equals(activeTaskId.getRackId())) {
          Optional<SingularityTask> maybeTask = taskManager.getActiveTask(activeTaskId.getId());
          checkTaskForDecomissionCleanup(requestIdsToReschedule, matchingTaskIds, maybeTask.get(), rack.toString());
        }
      }
    }
    
    for (String requestId : requestIdsToReschedule) {
      LOG.trace(String.format("Rescheduling request %s due to decomissions", requestId));
      requestManager.addToPendingQueue(new SingularityPendingRequestId(requestId));
    }
    
    for (SingularitySlave slave : slaves) {
      LOG.debug(String.format("Marking slave %s as decomissioned", slave));
      slaveManager.markAsDecomissioned(slave);
    }
    
    for (SingularityRack rack : racks) {
      LOG.debug(String.format("Marking rack %s as decomissioned", rack));
      rackManager.markAsDecomissioned(rack);
    }

    LOG.info(String.format("Found %s decomissioning slaves, %s decomissioning racks, rescheduling %s requests and scheduling %s tasks for cleanup in %sms", slaves.size(), racks.size(), requestIdsToReschedule.size(), matchingTaskIds.size(), System.currentTimeMillis() - start));
  }
  
  public void drainPendingQueue(final List<SingularityTaskId> activeTaskIds, final List<SingularityTaskId> cleaningTasks) {
    final long start = System.currentTimeMillis();
    
    final List<SingularityPendingRequestId> pendingRequests = requestManager.getPendingRequestIds();
    
    LOG.info(String.format("Pending queue had %s requests", pendingRequests.size()));
    
    if (pendingRequests.isEmpty()) {
      return;
    }
    
    final List<SingularityPendingTaskId> scheduledTasks = taskManager.getScheduledTasks();
    final List<String> decomissioningRacks = rackManager.getDecomissioning();
    final List<SingularitySlave> decomissioningSlaves = slaveManager.getDecomissioningObjects();
    
    int numScheduledTasks = 0;
    int obsoleteRequests = 0;
    
    for (SingularityPendingRequestId pendingRequest : pendingRequests) {
      Optional<SingularityRequest> maybeRequest = requestManager.fetchRequest(pendingRequest.getRequestId());
      
      if (maybeRequest.isPresent()) {
        checkForBounceAndAddToCleaningTasks(pendingRequest, activeTaskIds, cleaningTasks);
        
        numScheduledTasks += scheduleTasks(scheduledTasks, activeTaskIds, cleaningTasks, decomissioningRacks, decomissioningSlaves, maybeRequest.get(), pendingRequest.getPendingTypeEnum());
      } else {
        obsoleteRequests++;
      }
      
      requestManager.deletePendingRequest(pendingRequest.toString());
    }
    
    LOG.info(String.format("Scheduled %s requests (%s obsolete) in %sms", numScheduledTasks, obsoleteRequests, System.currentTimeMillis() - start));
  }
  
  private void checkForBounceAndAddToCleaningTasks(SingularityPendingRequestId pendingRequest, final List<SingularityTaskId> activeTaskIds, final List<SingularityTaskId> cleaningTasks) {
    if (pendingRequest.getPendingTypeEnum() != PendingType.BOUNCE) {
      return;
    }
    
    final long now = System.currentTimeMillis(); 
    
    int num = 0;
    
    for (SingularityTaskId matchingTaskId : getMatchingActiveTaskIds(pendingRequest.getRequestId(), activeTaskIds, cleaningTasks)) {
      LOG.debug(String.format("Adding task %s to cleanup (bounce)", matchingTaskId.getId()));
    
      num++;
      
      taskManager.createCleanupTask(new SingularityTaskCleanup(Optional.<String> absent(), TaskCleanupType.BOUNCING, now, matchingTaskId.getId(), pendingRequest.getRequestId()));
      cleaningTasks.add(matchingTaskId);
    }
    
    LOG.info(String.format("Added %s tasks for request %s to cleanup bounce queue in %sms", num, pendingRequest.getId(), System.currentTimeMillis() - now));
  }
    
  public List<SingularityTaskRequest> getDueTasks() {
    final List<SingularityPendingTaskId> tasks = taskManager.getScheduledTasks();
      
    final long now = System.currentTimeMillis();
    
    final List<SingularityPendingTaskId> dueTaskIds = Lists.newArrayListWithCapacity(tasks.size());
    
    for (SingularityPendingTaskId task : tasks) {
      if (task.getNextRunAt() <= now) {
        dueTaskIds.add(task);
      }
    }
    
    final List<SingularityTaskRequest> dueTasks = requestManager.fetchTasks(dueTaskIds);
    Collections.sort(dueTasks);
  
    return dueTasks;
  }
  
  private void deleteScheduledTasks(final List<SingularityPendingTaskId> scheduledTaskIds, String requestId) {
    for (SingularityPendingTaskId taskId : SingularityPendingTaskId.filter(scheduledTaskIds, requestId)) {
      taskManager.deleteScheduledTask(taskId.toString());
    }
  }

  public int scheduleTasks(final List<SingularityPendingTaskId> scheduledTaskIds, final List<SingularityTaskId> activeTaskIds, List<SingularityTaskId> cleaningTasks, List<String> decomissioningRacks, List<SingularitySlave> decomissioningSlaves, SingularityRequest request, PendingType pendingType) {
    deleteScheduledTasks(scheduledTaskIds, request.getId());
    
    final List<SingularityTaskId> matchingTaskIds = getMatchingActiveTaskIds(request.getId(), activeTaskIds, cleaningTasks);
    
    final int numMissingInstances = getNumMissingInstances(matchingTaskIds, request);

    if (numMissingInstances > 0) {
      final List<SingularityPendingTaskId> scheduledTasks = getScheduledTaskIds(numMissingInstances, matchingTaskIds, activeTaskIds, decomissioningRacks, decomissioningSlaves, request, pendingType);
      
      taskManager.persistScheduleTasks(scheduledTasks);
    } else if (numMissingInstances < 0) {
      LOG.debug(String.format("Missing instances is negative: %s, request %s, matching tasks: %s", numMissingInstances, request, matchingTaskIds));
      
      final long now = System.currentTimeMillis();
      
      for (int i = 0; i < Math.abs(numMissingInstances); i++) {
        final SingularityTaskId toCleanup = matchingTaskIds.get(i);
        
        LOG.info(String.format("Cleaning up task %s due to new request %s - scaling down to %s instances", toCleanup.getId(), request.getId(), request.getInstances()));
    
        taskManager.createCleanupTask(new SingularityTaskCleanup(Optional.<String> absent(), TaskCleanupType.SCALING_DOWN, now, toCleanup.getId(), request.getId()));
      }
    }
    
    return numMissingInstances;
  }
  
  public void handleCompletedTask(String stringTaskId, TaskState state) {
    SingularityTaskId taskId = SingularityTaskId.fromString(stringTaskId);
 
    Optional<SingularityRequest> maybeRequest = requestManager.fetchRequest(taskId.getRequestId());
    
    if (!maybeRequest.isPresent()) {
      // TODO what about failures?
      LOG.warn(String.format("Not scheduling a new task, due to no existing request for %s", taskId.getRequestId()));
      return;
    }
    
    SingularityRequest request = maybeRequest.get();
    
    PendingType pendingType = PendingType.REGULAR;
    
    if (MesosUtils.isTaskFailed(state)) {
      // TODO send an email every time?
      mailer.sendTaskFailedMail(taskId, request, state);
      
      // TODO how to handle this if there are running tasks that are working?
      if (shouldPause(request)) {
        mailer.sendRequestPausedMail(request);
        requestManager.createCleanupRequest(new SingularityRequestCleanup(Optional.<String> absent(), RequestCleanupType.PAUSING, System.currentTimeMillis(), request.getId()));
        return;
      }
      
      if (request.isScheduled() && shouldRetryImmediately(request)) {
        pendingType = PendingType.RETRY;
      }
    }
    
    scheduleOnCompletion(maybeRequest.get(), pendingType);
  }
  
  private boolean shouldRetryImmediately(SingularityRequest request) {
    if (request.getNumRetriesOnFailure() == null) {
      return false;
    }
   
    final int numRetriesInARow = getNumRetriesInARow(request);
    
    if (numRetriesInARow >= request.getNumRetriesOnFailure()) {
      LOG.debug(String.format("Request %s had %s retries in a row, not retrying again (num retries on failure: %s)", request.getId(), numRetriesInARow, request.getNumRetriesOnFailure()));
      return false;
    }
    
    LOG.debug(String.format("Request %s had %s retries in a row - retrying again (num retries on failure: %s)", request.getId(), numRetriesInARow, request.getNumRetriesOnFailure()));
    
    return true;
  } 
  
  private int getNumRetriesInARow(SingularityRequest request) {
    int retries = 0;
    
    List<SingularityTaskIdHistory> taskHistory = historyManager.getTaskHistoryForRequest(request.getId(), Optional.of(TaskHistoryOrderBy.createdAt), Optional.of(OrderDirection.DESC), 0, request.getNumRetriesOnFailure());
    
    for (SingularityTaskIdHistory history : taskHistory) { 
      if (history.getLastStatus().isPresent()) {
        TaskState taskState = TaskState.valueOf(history.getLastStatus().get());
        
        if (MesosUtils.isTaskFailed(taskState) && PendingType.valueOf(history.getPendingType()) == PendingType.RETRY) {
          retries++;
        } else {
          break;
        }
      }
    }
    
    return retries;
  }
  
  private boolean shouldPause(SingularityRequest request) {
    if (request.getMaxFailuresBeforePausing() == null) {
      return false;
    }
    
    final int pauseAfterNumFailedTasks = request.getMaxFailuresBeforePausing() + 1;
    
    if (request.getMaxFailuresBeforePausing() > 0) {
      SingularityRequestHistory lastUpdate = Iterables.getFirst(historyManager.getRequestHistory(request.getId(), Optional.of(RequestHistoryOrderBy.createdAt), Optional.of(OrderDirection.DESC), 0, 1), null);
      long lastUpdateAt = 0;
      
      if (lastUpdate != null) {
        lastUpdateAt = lastUpdate.getCreatedAt();
      } else {
        LOG.warn(String.format("Couldn't find a historical request for %s", request.getId()));
      }
            
      List<SingularityTaskIdHistory> taskHistory = historyManager.getTaskHistoryForRequest(request.getId(), Optional.of(TaskHistoryOrderBy.createdAt), Optional.of(OrderDirection.DESC), 0, pauseAfterNumFailedTasks);
      
      LOG.trace(String.format("Found %s historical tasks for request %s", taskHistory.size(), request.getId()));
      
      if (taskHistory.size() < pauseAfterNumFailedTasks) {
        return false;
      }
      
      for (SingularityTaskIdHistory history : taskHistory) {
        if (lastUpdateAt > history.getCreatedAt()) {
          LOG.debug(String.format("Not pausing request %s because task %s was launched at %s, before the last request update (%s)", request.getId(), history.getTaskId(), history.getCreatedAt(), lastUpdateAt));
          return false;
        }
        
        if (history.getLastStatus().isPresent()) {
          TaskState taskState = TaskState.valueOf(history.getLastStatus().get());
          
          if (!MesosUtils.isTaskFailed(taskState)) {
            LOG.debug(String.format("Task %s was not a failure (%s), so request %s is not paused", history.getTaskId(), taskState, request.getId())); 
                
            return false;
          }
        }
      }
    }
  
    LOG.info(String.format("Pausing request %s because it has failed at least %s tasks in a row", request.getId(), pauseAfterNumFailedTasks));
    
    return true;
  }
   
  private void scheduleOnCompletion(SingularityRequest request, PendingType pendingType) {
    final List<SingularityTaskId> activeTaskIds = taskManager.getActiveTaskIds();
    final List<SingularityPendingTaskId> scheduledTasks = taskManager.getScheduledTasks();
    final List<String> decomissioningRacks = rackManager.getDecomissioning();
    final List<SingularitySlave> decomissioningSlaves = slaveManager.getDecomissioningObjects();
    final List<SingularityTaskId> cleaningTasks = taskManager.getCleanupTaskIds();
    
    scheduleTasks(scheduledTasks, activeTaskIds, cleaningTasks, decomissioningRacks, decomissioningSlaves, request, pendingType);
  }
  
  private final int getNumMissingInstances(List<SingularityTaskId> matchingTaskIds, SingularityRequest request) {
    final int numInstances = request.getInstances();
    
    return numInstances - matchingTaskIds.size();
  }
  
  private List<SingularityPendingTaskId> getScheduledTaskIds(int numMissingInstances, List<SingularityTaskId> matchingTaskIds, List<SingularityTaskId> activeTaskIds, List<String> decomissioningRacks, List<SingularitySlave> decomissioningSlaves, SingularityRequest request, PendingType pendingType) {
    final long nextRunAt = getNextRunAt(request, pendingType);
  
    int highestInstanceNo = 0;

    for (SingularityTaskId matchingTaskId : matchingTaskIds) {
      if (matchingTaskId.getInstanceNo() > highestInstanceNo) {
        highestInstanceNo = matchingTaskId.getInstanceNo();
      }
    }
  
    final List<SingularityPendingTaskId> newTaskIds = Lists.newArrayListWithCapacity(numMissingInstances);
    
    for (int i = 0; i < numMissingInstances; i++) {
      newTaskIds.add(new SingularityPendingTaskId(request.getId(), nextRunAt, i + 1 + highestInstanceNo, pendingType));
    }
    
    return newTaskIds;
  }
  
  private long getNextRunAt(SingularityRequest request, PendingType pendingType) {
    long nextRunAt = System.currentTimeMillis();
    
    if (!request.isScheduled()) {
      return nextRunAt;
    }
    
    if (pendingType == PendingType.IMMEDIATE || pendingType == PendingType.RETRY) {
      LOG.info("Scheduling requested immediate run of %s", request.getId());
    } else {
      try {
        Date scheduleFrom = new Date();
        
        if (pendingType == PendingType.STARTUP) {
          // find out what the last time the task ran at was.
          Optional<SingularityTaskIdHistory> history = historyManager.getLastTaskForRequest(request.getId());
          if (history.isPresent()) {
            scheduleFrom = new Date(history.get().getUpdatedAt().or(history.get().getCreatedAt()));
          }
        }
        
        CronExpression cronExpression = new CronExpression(request.getSchedule());

        final Date nextRunAtDate = cronExpression.getNextValidTimeAfter(scheduleFrom);
        nextRunAt = nextRunAtDate.getTime();
        
        LOG.trace(String.format("Scheduling next run of %s (schedule: %s) at %s (from: %s)", request.getId(), request.getSchedule(), nextRunAtDate, scheduleFrom));
      } catch (ParseException pe) {
        throw Throwables.propagate(pe);
      }
    }
    
    return nextRunAt;
  }
}
