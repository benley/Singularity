package com.hubspot.singularity;

import com.google.common.base.Optional;
import com.google.common.collect.ListMultimap;

public class SingularityDeployStatisticsBuilder extends SingularityJsonObject {

  private final String requestId;
  private final String deployId;

  private int numSuccess;
  private int numFailures;

  private int numSequentialRetries;

  private ListMultimap<Integer, Long> instanceSequentialFailureTimestamps;

  private Optional<Long> lastFinishAt;
  private Optional<ExtendedTaskState> lastTaskState;

  public SingularityDeployStatisticsBuilder(String requestId, String deployId) {
    this.requestId = requestId;
    this.deployId = deployId;

    this.lastFinishAt = Optional.absent();
    this.lastTaskState = Optional.absent();
  }

  public SingularityDeployStatistics build() {
    return new SingularityDeployStatistics(requestId, deployId, numSuccess, numFailures, numSequentialRetries, lastFinishAt, lastTaskState, instanceSequentialFailureTimestamps);
  }

  public ListMultimap<Integer, Long> getInstanceSequentialFailureTimestamps() {
    return instanceSequentialFailureTimestamps;
  }

  public SingularityDeployStatisticsBuilder setInstanceSequentialFailureTimestamps(ListMultimap<Integer, Long> instanceSequentialFailureTimestamps) {
    this.instanceSequentialFailureTimestamps = instanceSequentialFailureTimestamps;
    return this;
  }

  public int getNumSuccess() {
    return numSuccess;
  }

  public SingularityDeployStatisticsBuilder setNumSuccess(int numSuccess) {
    this.numSuccess = numSuccess;
    return this;
  }

  public int getNumFailures() {
    return numFailures;
  }

  public SingularityDeployStatisticsBuilder setNumFailures(int numFailures) {
    this.numFailures = numFailures;
    return this;
  }

  public int getNumSequentialRetries() {
    return numSequentialRetries;
  }

  public SingularityDeployStatisticsBuilder setNumSequentialRetries(int numSequentialRetries) {
    this.numSequentialRetries = numSequentialRetries;
    return this;
  }

  public Optional<Long> getLastFinishAt() {
    return lastFinishAt;
  }

  public SingularityDeployStatisticsBuilder setLastFinishAt(Optional<Long> lastFinishAt) {
    this.lastFinishAt = lastFinishAt;
    return this;
  }

  public Optional<ExtendedTaskState> getLastTaskState() {
    return lastTaskState;
  }

  public SingularityDeployStatisticsBuilder setLastTaskState(Optional<ExtendedTaskState> lastTaskState) {
    this.lastTaskState = lastTaskState;
    return this;
  }

  public String getRequestId() {
    return requestId;
  }

  public String getDeployId() {
    return deployId;
  }

  @Override
  public String toString() {
    return "SingularityDeployStatisticsBuilder [requestId=" + requestId + ", deployId=" + deployId + ", numSuccess=" + numSuccess + ", numFailures=" + numFailures + ", numSequentialRetries="
        + numSequentialRetries + ", instanceSequentialFailureTimestamps=" + instanceSequentialFailureTimestamps + ", lastFinishAt=" + lastFinishAt + ", lastTaskState=" + lastTaskState + "]";
  }

}
