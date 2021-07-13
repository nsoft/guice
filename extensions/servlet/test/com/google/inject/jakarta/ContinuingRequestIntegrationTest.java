/*
 * Copyright (C) 2010 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.inject.jakarta;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.*;
import junit.framework.TestCase;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.easymock.EasyMock.*;

/** Tests continuation of requests */

public class ContinuingRequestIntegrationTest extends TestCase {
  private static final String PARAM_VALUE = "there";
  private static final String PARAM_NAME = "hi";

  private final AtomicBoolean failed = new AtomicBoolean(false);
  private final AbstractExecutorService sameThreadExecutor =
      new AbstractExecutorService() {
        @Override
        public void shutdown() {}

        @Override
        public List<Runnable> shutdownNow() {
          return ImmutableList.of();
        }

        @Override
        public boolean isShutdown() {
          return true;
        }

        @Override
        public boolean isTerminated() {
          return true;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
          return true;
        }

        @Override
        public void execute(Runnable command) {
          command.run();
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
          try {
            task.call();
            fail();
          } catch (Exception e) {
            // Expected.
            assertTrue(e instanceof IllegalStateException);
            failed.set(true);
          }

          return null;
        }
      };

  private ExecutorService executor;

  public final void testRequestContinuesInOtherThread()
      throws ServletException, IOException, InterruptedException {
    executor = Executors.newSingleThreadExecutor();

    Injector injector =
        Guice.createInjector(
            new ServletModule() {
              @Override
              protected void configureServlets() {
                serve("/*").with(ContinuingServlet.class);

                bind(ExecutorService.class).toInstance(executor);
              }
            });

    FilterConfig filterConfig = createMock(FilterConfig.class);
    expect(filterConfig.getServletContext()).andReturn(createMock(ServletContext.class));

    GuiceFilter guiceFilter = injector.getInstance(GuiceFilter.class);

    HttpServletRequest request = createMock(HttpServletRequest.class);
    HttpServletResponse response = createMock(HttpServletResponse.class);

    expect(request.getRequestURI()).andReturn("/");
    expect(request.getContextPath()).andReturn("").anyTimes();
    expect(request.getMethod()).andReturn("GET");
    expect(request.getCookies()).andReturn(new Cookie[0]);

    FilterChain filterChain = createMock(FilterChain.class);
    expect(request.getParameter(PARAM_NAME)).andReturn(PARAM_VALUE);

    replay(request, filterConfig, filterChain);

    guiceFilter.init(filterConfig);
    guiceFilter.doFilter(request, response, filterChain);

    // join.
    executor.shutdown();
    executor.awaitTermination(10, TimeUnit.SECONDS);

    assertEquals(PARAM_VALUE, injector.getInstance(OffRequestCallable.class).value);
    verify(request, filterConfig, filterChain);
  }

  public void testNothing() {}

  public final void testRequestContinuationDiesInHttpRequestThread()
      throws ServletException, IOException, InterruptedException {
    executor = sameThreadExecutor;
    Injector injector =
        Guice.createInjector(
            new ServletModule() {
              @Override
              protected void configureServlets() {
                serve("/*").with(ContinuingServlet.class);

                bind(ExecutorService.class).toInstance(executor);

                bind(SomeObject.class);
              }
            });

    FilterConfig filterConfig = createMock(FilterConfig.class);
    expect(filterConfig.getServletContext()).andReturn(createMock(ServletContext.class));

    GuiceFilter guiceFilter = injector.getInstance(GuiceFilter.class);

    HttpServletRequest request = createMock(HttpServletRequest.class);
    HttpServletResponse response = createMock(HttpServletResponse.class);

    expect(request.getRequestURI()).andReturn("/");
    expect(request.getContextPath()).andReturn("").anyTimes();

    expect(request.getMethod()).andReturn("GET");
    expect(request.getCookies()).andReturn(new Cookie[0]);
    FilterChain filterChain = createMock(FilterChain.class);

    replay(request, filterConfig, filterChain);

    guiceFilter.init(filterConfig);
    guiceFilter.doFilter(request, response, filterChain);

    // join.
    executor.shutdown();
    executor.awaitTermination(10, TimeUnit.SECONDS);

    assertTrue(failed.get());
    assertFalse(PARAM_VALUE.equals(injector.getInstance(OffRequestCallable.class).value));

    verify(request, filterConfig, filterChain);
  }

  @RequestScoped
  public static class SomeObject {}

  @Singleton
  public static class ContinuingServlet extends HttpServlet {
    @Inject OffRequestCallable callable;
    @Inject ExecutorService executorService;

    private SomeObject someObject;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
        throws ServletException, IOException {
      assertNull(someObject);

      // Seed with someobject.
      someObject = new SomeObject();
      Callable<String> task =
          ServletScopes.continueRequest(
              callable, ImmutableMap.<Key<?>, Object>of(Key.get(SomeObject.class), someObject));

      executorService.submit(task);
    }
  }

  @Singleton
  public static class OffRequestCallable implements Callable<String> {
    @Inject Provider<HttpServletRequest> request;
    @Inject Provider<HttpServletResponse> response;
    @Inject Provider<SomeObject> someObject;

    public String value;

    @Override
    public String call() throws Exception {
      assertNull(response.get());

      // Inside this request, we should always get the same instance.
      assertSame(someObject.get(), someObject.get());

      return value = request.get().getParameter(PARAM_NAME);
    }
  }
}
