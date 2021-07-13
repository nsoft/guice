// Copyright 2012 Google Inc. All Rights Reserved.

package com.google.inject.jakarta;

import junit.framework.TestCase;

import jakarta.servlet.http.HttpServletRequest;

import static org.easymock.EasyMock.*;

/**
 * Unit test for the servlet utility class.
 *
 * @author ntang@google.com (Michael Tang)
 */
public class ServletUtilsTest extends TestCase {
  public void testGetContextRelativePath() {
    assertEquals(
        "/test.html", getContextRelativePath("/a_context_path", "/a_context_path/test.html"));
    assertEquals("/test.html", getContextRelativePath("", "/test.html"));
    assertEquals("/test.html", getContextRelativePath("", "/foo/../test.html"));
    assertEquals("/test.html", getContextRelativePath("", "/././foo/../test.html"));
    assertEquals("/test.html", getContextRelativePath("", "/foo/../../../../test.html"));
    assertEquals("/test.html", getContextRelativePath("", "/foo/%2E%2E/test.html"));
    // %2E == '.'
    assertEquals("/test.html", getContextRelativePath("", "/foo/%2E%2E/test.html"));
    // %2F == '/'
    assertEquals("/foo/%2F/test.html", getContextRelativePath("", "/foo/%2F/test.html"));
    // %66 == 'f'
    assertEquals("/foo.html", getContextRelativePath("", "/%66oo.html"));
  }

  public void testGetContextRelativePath_preserveQuery() {
    assertEquals("/foo?q=f", getContextRelativePath("", "/foo?q=f"));
    assertEquals("/foo?q=%20+%20", getContextRelativePath("", "/foo?q=%20+%20"));
  }

  public void testGetContextRelativePathWithWrongPath() {
    assertNull(getContextRelativePath("/a_context_path", "/test.html"));
  }

  public void testGetContextRelativePathWithRootPath() {
    assertEquals("/", getContextRelativePath("/a_context_path", "/a_context_path"));
  }

  public void testGetContextRelativePathWithEmptyPath() {
    assertNull(getContextRelativePath("", ""));
  }

  public void testNormalizePath() {
    assertEquals("foobar", com.google.inject.jakarta.ServletUtils.normalizePath("foobar"));
    assertEquals("foo+bar", com.google.inject.jakarta.ServletUtils.normalizePath("foo+bar"));
    assertEquals("foo%20bar", com.google.inject.jakarta.ServletUtils.normalizePath("foo bar"));
    assertEquals("foo%25-bar", com.google.inject.jakarta.ServletUtils.normalizePath("foo%-bar"));
    assertEquals("foo%25+bar", com.google.inject.jakarta.ServletUtils.normalizePath("foo%+bar"));
    assertEquals("foo%25-0bar", com.google.inject.jakarta.ServletUtils.normalizePath("foo%-0bar"));
  }

  private String getContextRelativePath(String contextPath, String requestPath) {
    HttpServletRequest mock = createMock(HttpServletRequest.class);
    expect(mock.getContextPath()).andReturn(contextPath);
    expect(mock.getRequestURI()).andReturn(requestPath);
    replay(mock);
    String contextRelativePath = com.google.inject.jakarta.ServletUtils.getContextRelativePath(mock);
    verify(mock);
    return contextRelativePath;
  }
}
