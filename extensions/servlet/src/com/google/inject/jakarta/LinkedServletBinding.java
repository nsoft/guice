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

import com.google.inject.Key;

import jakarta.servlet.http.HttpServlet;

/**
 * A linked binding to a servlet.
 *
 * @author sameb@google.com
 * @since 3.0
 */
public interface LinkedServletBinding extends ServletModuleBinding {

  /** Returns the key used to lookup the servlet instance. */
  Key<? extends HttpServlet> getLinkedKey();
}