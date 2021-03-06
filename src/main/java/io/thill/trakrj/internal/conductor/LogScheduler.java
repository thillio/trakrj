/**
 * Copyright (c) 2019 Eric Thill
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
 */
package io.thill.trakrj.internal.conductor;

import io.thill.trakrj.Interval;
import io.thill.trakrj.Intervals;
import io.thill.trakrj.TrackerId;
import io.thill.trakrj.internal.conductor.RecordEvent.Type;
import io.thill.trakrj.internal.thread.SignalLatch;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Internal class. Public methods may change or be removed without warning.
 *
 * @author Eric Thill
 */
public class LogScheduler implements AutoCloseable {

  private final long MIN_SLEEP = TimeUnit.DAYS.toMillis(1);
  private final List<TrackerContext> trackers = new ArrayList<>();
  private final AtomicBoolean keepRunning = new AtomicBoolean(true);
  private final Object wakeup = new Object();
  private final SignalLatch shutdownCompleteLatch = new SignalLatch();
  private final RecordEventRingBuffer ringBuffer;
  private final Thread thread;

  public LogScheduler(RecordEventRingBuffer ringBuffer) {
    this.ringBuffer = ringBuffer;
    this.thread = new Thread(this::loop, "TrakrJ-Scheduler");
    this.thread.setDaemon(true);
  }

  public void start() {
    thread.start();
  }

  @Override
  public void close() {
    keepRunning.set(false);
    thread.interrupt();
    shutdownCompleteLatch.await();
  }

  private void loop() {
    try {
      while(keepRunning.get()) {
        final long now = System.currentTimeMillis();
        long nearestDispatch = now + MIN_SLEEP;
        synchronized(trackers) {
          for(int i = 0; i < trackers.size(); i++) {
            TrackerContext tc = trackers.get(i);
            long timeToDispatch = tc.nextLogDispatch - now;
            // check if this Tracker's dispatch time has surpassed
            if(timeToDispatch <= 0) {
              // check if this Tracker's reset time has also surpassed
              long timeToReset = tc.nextResetDispatch - now;
              if(timeToReset <= 0) {
                dispatch(tc.id, Type.LOG_AND_RESET, tc.nextLogDispatch);
              } else {
                dispatch(tc.id, Type.LOG, tc.nextLogDispatch);
              }
              scheduleNextDispatch(tc);
            }
            if(tc.nextLogDispatch < nearestDispatch) {
              nearestDispatch = tc.nextLogDispatch;
            }
          }
        }
        // sleep until next dispatch
        long sleep = nearestDispatch - System.currentTimeMillis();
        trySleep(sleep);
      }
    } finally {
      shutdownCompleteLatch.signal();
    }
  }

  private void dispatch(TrackerId id, Type type, long timestamp) {
    RecordEvent event = ringBuffer.claim();
    event.setType(type);
    event.setId(id);
    event.setTimestamp(timestamp);
    ringBuffer.commit(event);
  }

  public void add(TrackerId id, Interval logInterval, Interval resetInterval) {
    if(logInterval == null) {
      // null -> never log
      logInterval = Intervals.never();
    }
    if(resetInterval == null) {
      // null -> never reset
      resetInterval = Intervals.never();
    }
    TrackerContext tc = new TrackerContext(id, logInterval, resetInterval);
    scheduleFirstDispatch(tc);
    synchronized(trackers) {
      trackers.add(tc);
    }
    synchronized(wakeup) {
      wakeup.notifyAll();
    }
  }

  private void scheduleFirstDispatch(TrackerContext tc) {
    final long now = System.currentTimeMillis();
    long logDispatch = tc.logInterval.first(now);
    long resetDispatch = tc.resetInterval.first(now);
    tc.nextLogDispatch = logDispatch == Interval.NEVER ? Long.MAX_VALUE : logDispatch;
    tc.nextResetDispatch = resetDispatch == Interval.NEVER ? Long.MAX_VALUE : resetDispatch;
  }

  private void scheduleNextDispatch(TrackerContext tc) {
    if(tc.nextLogDispatch >= tc.nextResetDispatch) {
      // advance reset dispatch if current log dispatch has surpassed it
      long nextReset = tc.resetInterval.next(tc.nextResetDispatch);
      tc.nextResetDispatch = nextReset == Interval.NEVER ? Long.MAX_VALUE : nextReset;
    }
    long nextLog = tc.logInterval.next(tc.nextLogDispatch);
    tc.nextLogDispatch = nextLog == Interval.NEVER ? Long.MAX_VALUE : nextLog;
  }

  private void trySleep(long sleep) {
    if(sleep > 0) {
      try {
        synchronized(wakeup) {
          wakeup.wait(sleep);
        }
      } catch(InterruptedException e) {
        return;
      }
    }
  }

  private static class TrackerContext {
    public final TrackerId id;
    public final Interval logInterval;
    public final Interval resetInterval;
    public long nextLogDispatch;
    public long nextResetDispatch;

    public TrackerContext(TrackerId id, Interval logInterval, Interval resetInterval) {
      this.id = id;
      this.logInterval = logInterval;
      this.resetInterval = resetInterval;
    }
  }
}
