# minijar

MiniJar is a tool for jar reduction. It takes an executable jar and a set of entrypoints, and outputs a jar where unreachable methods have been removed. Minijar uses static analysis (WALA, https://github.com/WALA/wala) to build a call graph in order to determine which methods are reachable. It takes into account class hierarchies, abstract methods/classes, and interfaces. 

## Installation & Usage

To install MiniJar, clone this project and build:

```
./gradlew build
```

To run MiniJar:
```
./gradlew cmdLineJavaExec -Pargs="-Xmx10000m <jarfile>  -m <mainClass> -d <scopeDataFile> -o <jarFile>"
```

MiniJar accepts the following arguments:
```<jarfile>                 Path to the jar file to reduce
   -d <scopeDataFile>        Path to a scope data file 
   -m <mainClass>            Provide one or more mainClasses
   -e <entryClass>           Provide one or more entryClasses (optional)
   -p <entryPointFile>       Provide an entrypoint file (optional)
   -o <jarfile>              Path to the output jar file 
```

The `scopeDataFile` is a required argument and is a path to a file of the form:
```
Primordial,Java,jrt,java.base
Primordial,Java,jarFile,primordial.jar.model
Application,Java,jarFile,<jarname>
```

The `scopeDataFile` and `<jarname>` must be located on the Java class path.


Example:
```./gradlew cmdLineJavaExec -Pargs="-Xmx10000m src/main/resources/Example-0.1.0.jar  -m Lshape/Example -d wala.data.example.txt -o out.jar"```

The `wala.data.example.txt` scope data file contains:
```
Primordial,Java,jrt,java.base
Primordial,Java,jarFile,primordial.jar.model
Application,Java,jarFile,Example-0.1.0.jar
```

Notice that the `mainClass` and `entryClass` are specified in standard Java descriptor format (e.g., `Lshape/Example`).
Similarly the entrypoint file contains a list of methods specified in this standard format (e.g., `Lorg/jboss/logmanager/LogManager#<init>()V`).

Entryclasses and entrypoint files can be used to ensure that the call graph algorithm takes these methods into account so that they are guaranteed to be reachable. This can be useful when the algorithm misses methods due to reflection.

The result of running the above `.gradlew` command is an output jar `out.jar` that is similar to the input jar `Example-0.1.0.jar` but with unreachable methods removed. The output jar is also executable and leads to the same ouptut as the original jar when executed (via `java -jar out.jar`).




## Limitations

MiniJar currently accepts a single jar file as input. 
It also does not handle all usages of reflection.