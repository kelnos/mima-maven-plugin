package org.spurint.maven.plugins.mima.test;

public class TestLibrary {
    public String testMethod(final String s) {
        return s + s;
    }

    public int otherTestMethod() {
        return 42;
    }
}
