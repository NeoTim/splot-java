/*
 * Copyright (C) 2018 Google Inc.
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
package com.google.iot.m2m.base;

/**
 * Unchecked exception thrown by {@link Technology} instances (and their native {@link
 * Thing} instances) indicating a serious technology-specific runtime error has
 * occurred.
 */
public class TechnologyRuntimeException extends RuntimeException {
    public TechnologyRuntimeException() {}

    public TechnologyRuntimeException(String reason) {
        super(reason);
    }

    public TechnologyRuntimeException(String reason, Throwable t) {
        super(reason, t);
    }

    public TechnologyRuntimeException(Throwable t) {
        super(t);
    }
}
