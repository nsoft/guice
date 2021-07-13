package com.google.inject.jakarta;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Singleton;
import junit.framework.TestCase;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;

import static org.easymock.EasyMock.*;

/**
 * This tests that filter stage of the pipeline dispatches correctly to guice-managed filters with
 * multiple modules.
 *
 * <p>WARNING(dhanji): Non-parallelizable test =(
 *
 * @author dhanji@gmail.com (Dhanji R. Prasanna)
 */
public class MultiModuleDispatchIntegrationTest extends TestCase {
  private static int inits, doFilters, destroys;

  @Override
  public final void setUp() {
    inits = 0;
    doFilters = 0;
    destroys = 0;

    GuiceFilter.reset();
  }

  public final void testDispatchRequestToManagedPipeline() throws ServletException, IOException {
    final Injector injector =
        Guice.createInjector(
            new ServletModule() {

              @Override
              protected void configureServlets() {
                filter("/*").through(TestFilter.class);

                // These filters should never fire
                filter("*.jsp").through(Key.get(TestFilter.class));
              }
            },
            new ServletModule() {

              @Override
              protected void configureServlets() {
                filter("*.html").through(TestFilter.class);
                filter("/*").through(Key.get(TestFilter.class));

                // These filters should never fire
                filter("/index/*").through(Key.get(TestFilter.class));
              }
            });

    final com.google.inject.jakarta.FilterPipeline pipeline = injector.getInstance(com.google.inject.jakarta.FilterPipeline.class);
    pipeline.initPipeline(null);

    //create ourselves a mock request with test URI
    HttpServletRequest requestMock = createMock(HttpServletRequest.class);

    expect(requestMock.getRequestURI()).andReturn("/index.html").anyTimes();
    expect(requestMock.getContextPath()).andReturn("").anyTimes();

    //dispatch request
    replay(requestMock);
    pipeline.dispatch(requestMock, null, createMock(FilterChain.class));
    pipeline.destroyPipeline();

    verify(requestMock);

    assertTrue(
        "lifecycle states did not"
            + " fire correct number of times-- inits: "
            + inits
            + "; dos: "
            + doFilters
            + "; destroys: "
            + destroys,
        inits == 1 && doFilters == 3 && destroys == 1);
  }

  @Singleton
  public static class TestFilter implements Filter {
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
      inits++;
    }

    @Override
    public void doFilter(
        ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
        throws IOException, ServletException {
      doFilters++;
      filterChain.doFilter(servletRequest, servletResponse);
    }

    @Override
    public void destroy() {
      destroys++;
    }
  }
}