/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */

package io.strimzi.systemtest.timemeasuring;

public enum Operation {
    CLASS_EXECUTION,
    ROLLING_UPDATE,
    SCALE_UP,
    SCALE_DOWN,
    TEST_EXECUTION,
}