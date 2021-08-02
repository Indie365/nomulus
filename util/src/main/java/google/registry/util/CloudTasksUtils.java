// Copyright 2021 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.util;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.appengine.api.taskqueue.TransientFailureException;
import com.google.cloud.tasks.v2.AppEngineHttpRequest;
import com.google.cloud.tasks.v2.AppEngineRouting;
import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.HttpMethod;
import com.google.cloud.tasks.v2.QueueName;
import com.google.cloud.tasks.v2.Task;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Streams;
import com.google.common.escape.Escaper;
import com.google.common.flogger.FluentLogger;
import com.google.common.net.HttpHeaders;
import com.google.common.net.MediaType;
import com.google.common.net.UrlEscapers;
import com.google.protobuf.ByteString;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Utilities for dealing with Cloud Tasks.
 *
 * <p>Note that there is no local emulator for Cloud Tasks, and we have to store the tasks ourselves
 * in unit tests. This classes also handles that so the caller does not need to.
 */
public class CloudTasksUtils implements Serializable {

  private static final long serialVersionUID = -7605156291755534069L;
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  @Nullable private final LinkedListMultimap<String, Task> testTasks;
  @Nullable private final String projectId;
  @Nullable private final String locationId;
  private final Retrier retrier;

  public CloudTasksUtils(
      Retrier retrier,
      String projectId,
      String locationId,
      LinkedListMultimap<String, Task> testTasks) {
    this.retrier = retrier;
    this.projectId = projectId;
    this.locationId = locationId;
    this.testTasks = testTasks;
  }

  public Task enqueue(String queue, Task task) {
    if (testTasks != null) {
      if (task.getName().isEmpty()) {
        Task newTask = task.toBuilder().setName(String.format("test-%d", testTasks.size())).build();
        testTasks.put(queue, newTask);
        return newTask;
      } else {
        testTasks.put(queue, task);
        return task;
      }
    }
    return retrier.callWithRetry(
        () -> {
          logger.atInfo().log(
              "Enqueuing queue='%s' endpoint='%s'",
              queue, task.getAppEngineHttpRequest().getRelativeUri());
          try (CloudTasksClient client = CloudTasksClient.create()) {
            return client.createTask(QueueName.of(projectId, locationId, queue), task);
          }
        },
        TransientFailureException.class);
  }

  public ImmutableList<Task> enqueue(String queue, Iterable<Task> tasks) {
    return Streams.stream(tasks).map(task -> enqueue(queue, task)).collect(toImmutableList());
  }

  public List<Task> getTestTasksFor(String queue) {
    return testTasks.get(queue);
  }

  public static CloudTasksUtils creatForTest() {
    return new CloudTasksUtils(null, null, null, LinkedListMultimap.create());
  }

  /**
   * Create a {@link Task} to be enqueued.
   *
   * @param path the relative URI (staring with a slash and ending without one).
   * @param method the HTTP method to be used for the request, only GET and POST are supported.
   * @param service the App Engine service to route the request to. Note that with App Engine task
   *     queues if no service is specified, the service which enqueues the task will be used to
   *     process the task. Cloud Tasks API does not support this feature so the service will always
   *     needs to be explicitly specified.
   * @param params A map of URL query parameters.
   * @return the enqueued task.
   * @see <a
   *     href=ttps://cloud.google.com/appengine/docs/standard/java/taskqueue/push/creating-tasks#target>Specifyinig
   *     the worker service</a>
   */
  private static Task createTask(
      String path, HttpMethod method, String service, Multimap<String, String> params) {
    AppEngineHttpRequest.Builder requestBuilder =
        AppEngineHttpRequest.newBuilder()
            .setHttpMethod(method)
            .setAppEngineRouting(AppEngineRouting.newBuilder().setService(service).build());
    Escaper escaper = UrlEscapers.urlPathSegmentEscaper();
    String encodedParams =
        Joiner.on("&")
            .join(
                params.asMap().entrySet().stream()
                    .map(
                        entry ->
                            String.format(
                                "%s=%s",
                                escaper.escape(entry.getKey()),
                                escaper.escape(Joiner.on(",").join(entry.getValue()))))
                    .collect(toImmutableList()));
    if (method == HttpMethod.GET) {
      path = String.format("%s?%s", path, encodedParams);
    } else if (method == HttpMethod.POST) {
      requestBuilder
          .putHeaders(HttpHeaders.CONTENT_TYPE, MediaType.FORM_DATA.toString())
          .setBody(ByteString.copyFrom(encodedParams, StandardCharsets.UTF_8));
    } else {
      throw new IllegalArgumentException(String.format("HTTP method %s is not allowed.", method));
    }
    requestBuilder.setRelativeUri(path);
    return Task.newBuilder().setAppEngineHttpRequest(requestBuilder.build()).build();
  }

  public static Task createPostTask(String path, String service, Multimap<String, String> params) {
    return createTask(path, HttpMethod.POST, service, params);
  }

  public static Task createGetTask(String path, String service, Multimap<String, String> params) {
    return createTask(path, HttpMethod.GET, service, params);
  }
}
