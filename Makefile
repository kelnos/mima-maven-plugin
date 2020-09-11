.PHONY: all clean install test

PLUGIN_VERSION := $(shell mvn help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null)

all:

install:
	mvn install

test: install
	cd test-projects && ./run-tests.sh $(PLUGIN_VERSION)

clean:
	mvn clean
