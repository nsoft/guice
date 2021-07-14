/*
 * Copyright (C) 2006 Google Inc.
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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.inject.Module;
import com.google.inject.*;
import com.google.inject.internal.Annotations;
import com.google.inject.jakarta.ServletScopes.NullObject;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import com.google.inject.util.Providers;
import junit.framework.TestCase;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.Ignore;

import java.io.IOException;
import java.io.Serializable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Map;

import static com.google.inject.Asserts.assertContains;
import static com.google.inject.Asserts.reserialize;
import static com.google.inject.jakarta.ServletTestUtils.newFakeHttpServletRequest;
import static com.google.inject.jakarta.ServletTestUtils.newFakeHttpServletResponse;
import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.easymock.EasyMock.createMock;

/** @author crazybob@google.com (Bob Lee) */

public class ServletTest extends TestCase {
  private static final Key<HttpServletRequest> HTTP_REQ_KEY = Key.get(HttpServletRequest.class);
  private static final Key<HttpServletResponse> HTTP_RESP_KEY = Key.get(HttpServletResponse.class);
  private static final Key<Map<String, String[]>> REQ_PARAMS_KEY =
      new Key<Map<String, String[]>>(RequestParameters.class) {};

  private static final Key<InRequest> IN_REQUEST_NULL_KEY = Key.get(InRequest.class, Null.class);
  private static final Key<InSession> IN_SESSION_KEY = Key.get(InSession.class);
  private static final Key<InSession> IN_SESSION_NULL_KEY = Key.get(InSession.class, Null.class);

  @Override
  public void setUp() {
    //we need to clear the reference to the pipeline every test =(
    com.google.inject.jakarta.GuiceFilter.reset();
  }


  public void testScopeExceptions() throws Exception {
    Injector injector =
        Guice.createInjector(
            new AbstractModule() {
              @Override
              protected void configure() {
                install(new com.google.inject.jakarta.ServletModule());
              }

              @Provides
              @com.google.inject.jakarta.RequestScoped
              String provideString() {
                return "foo";
              }

              @Provides
              @com.google.inject.jakarta.SessionScoped
              Integer provideInteger() {
                return 1;
              }

              @Provides
              @com.google.inject.jakarta.RequestScoped
              @Named("foo")
              String provideNamedString() {
                return "foo";
              }
            });

    try {
      injector.getInstance(String.class);
      fail();
    } catch (ProvisionException oose) {
      assertContains(oose.getMessage(), "Cannot access scoped [String].");
    }

    try {
      injector.getInstance(Integer.class);
      fail();
    } catch (ProvisionException oose) {
      assertContains(oose.getMessage(), "Cannot access scoped [Integer].");
    }

    Key<?> key = Key.get(String.class, Names.named("foo"));
    try {
      injector.getInstance(key);
      fail();
    } catch (ProvisionException oose) {
      assertContains(
          oose.getMessage(),
          "Cannot access scoped [String annotated with @Named("
              + Annotations.memberValueString("value", "foo")
              + ")]");
    }
  }

  public void testRequestAndResponseBindings() throws Exception {
    final Injector injector = createInjector();
    final HttpServletRequest request = newFakeHttpServletRequest();
    final HttpServletResponse response = newFakeHttpServletResponse();

    final boolean[] invoked = new boolean[1];
    com.google.inject.jakarta.GuiceFilter filter = new com.google.inject.jakarta.GuiceFilter();
    FilterChain filterChain =
        new FilterChain() {
          @Override
          public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse) {
            invoked[0] = true;
            assertSame(request, servletRequest);
            assertSame(request, injector.getInstance(ServletRequest.class));
            assertSame(request, injector.getInstance(HTTP_REQ_KEY));

            assertSame(response, servletResponse);
            assertSame(response, injector.getInstance(ServletResponse.class));
            assertSame(response, injector.getInstance(HTTP_RESP_KEY));

            assertSame(servletRequest.getParameterMap(), injector.getInstance(REQ_PARAMS_KEY));
          }
        };
    filter.doFilter(request, response, filterChain);

    assertTrue(invoked[0]);
  }

  public void testRequestAndResponseBindings_wrappingFilter() throws Exception {
    final HttpServletRequest request = newFakeHttpServletRequest();
    final ImmutableMap<String, String[]> wrappedParamMap =
        ImmutableMap.of("wrap", new String[] {"a", "b"});
    final HttpServletRequestWrapper requestWrapper =
        new HttpServletRequestWrapper(request) {
          @Override
          public Map<String, String[]> getParameterMap() {
            return wrappedParamMap;
          }

          @Override
          public Object getAttribute(String attr) {
            // Ensure that attributes are stored on the original request object.
            throw new UnsupportedOperationException();
          }
        };
    final HttpServletResponse response = newFakeHttpServletResponse();
    final HttpServletResponseWrapper responseWrapper = new HttpServletResponseWrapper(response);

    final boolean[] filterInvoked = new boolean[1];
    final Injector injector =
        createInjector(
            new com.google.inject.jakarta.ServletModule() {
              @Override
              protected void configureServlets() {
                filter("/*")
                    .through(
                        new Filter() {
                          @Inject Provider<ServletRequest> servletReqProvider;
                          @Inject Provider<HttpServletRequest> reqProvider;
                          @Inject Provider<ServletResponse> servletRespProvider;
                          @Inject Provider<HttpServletResponse> respProvider;

                          @Override
                          public void init(FilterConfig filterConfig) {}

                          @Override
                          public void doFilter(
                              ServletRequest req, ServletResponse resp, FilterChain chain)
                              throws IOException, ServletException {
                            filterInvoked[0] = true;
                            assertSame(req, servletReqProvider.get());
                            assertSame(req, reqProvider.get());

                            assertSame(resp, servletRespProvider.get());
                            assertSame(resp, respProvider.get());

                            chain.doFilter(requestWrapper, responseWrapper);

                            assertSame(req, reqProvider.get());
                            assertSame(resp, respProvider.get());
                          }

                          @Override
                          public void destroy() {}
                        });
              }
            });

    com.google.inject.jakarta.GuiceFilter filter = new com.google.inject.jakarta.GuiceFilter();
    final boolean[] chainInvoked = new boolean[1];
    FilterChain filterChain =
        new FilterChain() {
          @Override
          public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse) {
            chainInvoked[0] = true;
            assertSame(requestWrapper, servletRequest);
            assertSame(requestWrapper, injector.getInstance(ServletRequest.class));
            assertSame(requestWrapper, injector.getInstance(HTTP_REQ_KEY));

            assertSame(responseWrapper, servletResponse);
            assertSame(responseWrapper, injector.getInstance(ServletResponse.class));
            assertSame(responseWrapper, injector.getInstance(HTTP_RESP_KEY));

            assertSame(servletRequest.getParameterMap(), injector.getInstance(REQ_PARAMS_KEY));

            InRequest inRequest = injector.getInstance(InRequest.class);
            assertSame(inRequest, injector.getInstance(InRequest.class));
          }
        };
    filter.doFilter(request, response, filterChain);

    assertTrue(chainInvoked[0]);
    assertTrue(filterInvoked[0]);
  }

  public void testRequestAndResponseBindings_matchesPassedParameters() throws Exception {
    final int[] filterInvoked = new int[1];
    final boolean[] servletInvoked = new boolean[1];
    createInjector(
        new com.google.inject.jakarta.ServletModule() {
          @Override
          protected void configureServlets() {
            final HttpServletRequest[] previousReq = new HttpServletRequest[1];
            final HttpServletResponse[] previousResp = new HttpServletResponse[1];

            final Provider<ServletRequest> servletReqProvider = getProvider(ServletRequest.class);
            final Provider<HttpServletRequest> reqProvider = getProvider(HttpServletRequest.class);
            final Provider<ServletResponse> servletRespProvider =
                getProvider(ServletResponse.class);
            final Provider<HttpServletResponse> respProvider =
                getProvider(HttpServletResponse.class);

            Filter filter =
                new Filter() {
                  @Override
                  public void init(FilterConfig filterConfig) {}

                  @Override
                  public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
                      throws IOException, ServletException {
                    filterInvoked[0]++;
                    assertSame(req, servletReqProvider.get());
                    assertSame(req, reqProvider.get());
                    if (previousReq[0] != null) {
                      assertEquals(req, previousReq[0]);
                    }

                    assertSame(resp, servletRespProvider.get());
                    assertSame(resp, respProvider.get());
                    if (previousResp[0] != null) {
                      assertEquals(resp, previousResp[0]);
                    }

                    chain.doFilter(
                        previousReq[0] = new HttpServletRequestWrapper((HttpServletRequest) req),
                        previousResp[0] =
                            new HttpServletResponseWrapper((HttpServletResponse) resp));

                    assertSame(req, reqProvider.get());
                    assertSame(resp, respProvider.get());
                  }

                  @Override
                  public void destroy() {}
                };

            filter("/*").through(filter);
            filter("/*").through(filter); // filter twice to test wrapping in filters
            serve("/*")
                .with(
                    new HttpServlet() {
                      @Override
                      protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
                        servletInvoked[0] = true;
                        assertSame(req, servletReqProvider.get());
                        assertSame(req, reqProvider.get());

                        assertSame(resp, servletRespProvider.get());
                        assertSame(resp, respProvider.get());
                      }
                    });
          }
        });

    com.google.inject.jakarta.GuiceFilter filter = new com.google.inject.jakarta.GuiceFilter();
    filter.doFilter(
        newFakeHttpServletRequest(),
        newFakeHttpServletResponse(),
        new FilterChain() {
          @Override
          public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse) {
            throw new IllegalStateException("Shouldn't get here");
          }
        });

    assertEquals(2, filterInvoked[0]);
    assertTrue(servletInvoked[0]);
  }

  public void testNewRequestObject() throws CreationException, IOException, ServletException {
    final Injector injector = createInjector();
    final HttpServletRequest request = newFakeHttpServletRequest();

    com.google.inject.jakarta.GuiceFilter filter = new com.google.inject.jakarta.GuiceFilter();
    final boolean[] invoked = new boolean[1];
    FilterChain filterChain =
        new FilterChain() {
          @Override
          public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse) {
            invoked[0] = true;
            assertNotNull(injector.getInstance(InRequest.class));
            assertNull(injector.getInstance(IN_REQUEST_NULL_KEY));
          }
        };

    filter.doFilter(request, null, filterChain);

    assertTrue(invoked[0]);
  }

  public void testExistingRequestObject() throws CreationException, IOException, ServletException {
    final Injector injector = createInjector();
    final HttpServletRequest request = newFakeHttpServletRequest();

    com.google.inject.jakarta.GuiceFilter filter = new com.google.inject.jakarta.GuiceFilter();
    final boolean[] invoked = new boolean[1];
    FilterChain filterChain =
        new FilterChain() {
          @Override
          public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse) {
            invoked[0] = true;

            InRequest inRequest = injector.getInstance(InRequest.class);
            assertSame(inRequest, injector.getInstance(InRequest.class));

            assertNull(injector.getInstance(IN_REQUEST_NULL_KEY));
            assertNull(injector.getInstance(IN_REQUEST_NULL_KEY));
          }
        };

    filter.doFilter(request, null, filterChain);

    assertTrue(invoked[0]);
  }

  public void testNewSessionObject() throws CreationException, IOException, ServletException {
    final Injector injector = createInjector();
    final HttpServletRequest request = newFakeHttpServletRequest();

    com.google.inject.jakarta.GuiceFilter filter = new com.google.inject.jakarta.GuiceFilter();
    final boolean[] invoked = new boolean[1];
    FilterChain filterChain =
        new FilterChain() {
          @Override
          public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse) {
            invoked[0] = true;
            assertNotNull(injector.getInstance(InSession.class));
            assertNull(injector.getInstance(IN_SESSION_NULL_KEY));
          }
        };

    filter.doFilter(request, null, filterChain);

    assertTrue(invoked[0]);
  }

  public void testExistingSessionObject() throws CreationException, IOException, ServletException {
    final Injector injector = createInjector();
    final HttpServletRequest request = newFakeHttpServletRequest();

    com.google.inject.jakarta.GuiceFilter filter = new com.google.inject.jakarta.GuiceFilter();
    final boolean[] invoked = new boolean[1];
    FilterChain filterChain =
        new FilterChain() {
          @Override
          public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse) {
            invoked[0] = true;

            InSession inSession = injector.getInstance(InSession.class);
            assertSame(inSession, injector.getInstance(InSession.class));

            assertNull(injector.getInstance(IN_SESSION_NULL_KEY));
            assertNull(injector.getInstance(IN_SESSION_NULL_KEY));
          }
        };

    filter.doFilter(request, null, filterChain);

    assertTrue(invoked[0]);
  }

  public void testHttpSessionIsSerializable() throws Exception {
    final Injector injector = createInjector();
    final HttpServletRequest request = newFakeHttpServletRequest();
    final HttpSession session = request.getSession();

    com.google.inject.jakarta.GuiceFilter filter = new com.google.inject.jakarta.GuiceFilter();
    final boolean[] invoked = new boolean[1];
    FilterChain filterChain =
        new FilterChain() {
          @Override
          public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse) {
            invoked[0] = true;
            assertNotNull(injector.getInstance(InSession.class));
            assertNull(injector.getInstance(IN_SESSION_NULL_KEY));
          }
        };

    filter.doFilter(request, null, filterChain);

    assertTrue(invoked[0]);

    HttpSession deserializedSession = reserialize(session);

    String inSessionKey = IN_SESSION_KEY.toString();
    String inSessionNullKey = IN_SESSION_NULL_KEY.toString();
    assertTrue(deserializedSession.getAttribute(inSessionKey) instanceof InSession);
    assertEquals(NullObject.INSTANCE, deserializedSession.getAttribute(inSessionNullKey));
  }

  public void testGuiceFilterConstructors() throws Exception {
    final RuntimeException servletException = new RuntimeException();
    final RuntimeException chainException = new RuntimeException();
    HttpServletResponse mock = createMock(HttpServletResponse.class);
    final Injector injector =
        createInjector(
            new com.google.inject.jakarta.ServletModule() {
              @Override
              protected void configureServlets() {
                serve("/*")
                    .with(
                        new HttpServlet() {
                          @Override
                          protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
                            throw servletException;
                          }
                        });
              }
            });
    final HttpServletRequest request = newFakeHttpServletRequest();
    FilterChain filterChain =
        new FilterChain() {
          @Override
          public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse) {
            throw chainException;
          }
        };

    try {
        new com.google.inject.jakarta.GuiceFilter().doFilter(request, mock, filterChain);
      fail();
    } catch (RuntimeException e) {
      assertSame(servletException, e);
    }
    try {
      injector.getInstance(com.google.inject.jakarta.GuiceFilter.class).doFilter(request, mock, filterChain);
      fail();
    } catch (RuntimeException e) {
      assertSame(servletException, e);
    }
    try {
      injector
          .getInstance(Key.get(GuiceFilter.class, ScopingOnly.class))
          .doFilter(request, mock, filterChain);
      fail();
    } catch (RuntimeException e) {
      assertSame(chainException, e);
    }
  }

  private Injector createInjector(Module... modules) throws CreationException {
    return Guice.createInjector(
        Lists.<Module>asList(
            new AbstractModule() {
              @Override
              protected void configure() {
                install(new ServletModule());
                bind(InSession.class);
                bind(IN_SESSION_NULL_KEY)
                    .toProvider(Providers.<InSession>of(null))
                    .in(com.google.inject.jakarta.SessionScoped.class);
                bind(InRequest.class);
                bind(IN_REQUEST_NULL_KEY)
                    .toProvider(Providers.<InRequest>of(null))
                    .in(com.google.inject.jakarta.RequestScoped.class);
              }
            },
            modules));
  }

  @SessionScoped
  static class InSession implements Serializable {}

  @RequestScoped
  static class InRequest {}

  @BindingAnnotation
  @Retention(RUNTIME)
  @Target({PARAMETER, METHOD, FIELD})
  @interface Null {}
}
