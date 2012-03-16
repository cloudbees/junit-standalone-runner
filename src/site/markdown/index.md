# What is this?

This stand-alone program runs your compiled JUnit4 test cases and generate JUnit report files.
It contains everything you need to run the tests.
With this driver, you can build and package test code, then run it elsewhere later as a binary with
minimum dependency.

This project was originally developed for running [Apache BigTop tests](http://incubator.apache.org/bigtop/).
These are integration tests that need to run on arbitrary target systems. In such environment, it is better
not to expect the presence of Ant/Maven in the test target, and it is better to take the test as a binary artifact
so as not to requier JDK on the target system.

# Usage

Package your test code into a jar file (or jar files), and place them all into a single directory along
with all your dependencies. Then run the tool as follows:

    java -jar junit-standalone-runner-1.0-jar-with-dependencies.jar -report path/to/report path/to/jardir

The program will scan all your classes for the `@Test` annotation, then run them all. Report files
are created and stored into the specified directory in the XML format.