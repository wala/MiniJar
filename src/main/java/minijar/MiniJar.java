/*
 * This Java source file was generated by the Gradle 'init' task.
 */
/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package minijar;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;

import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.core.java11.Java9AnalysisScopeReader;
import com.ibm.wala.core.util.warnings.Warnings;
import com.ibm.wala.core.util.strings.StringStuff;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.CallGraphBuilder;
import com.ibm.wala.ipa.callgraph.CallGraphBuilderCancelException;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.DefaultEntrypoint;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.shrike.shrikeBT.shrikeCT.ClassInstrumenter;
import com.ibm.wala.shrike.shrikeBT.shrikeCT.OfflineInstrumenter;
import com.ibm.wala.shrike.shrikeCT.ClassReader;
import com.ibm.wala.shrike.shrikeCT.ClassWriter;
import com.ibm.wala.shrike.shrikeCT.ConstantPoolParser;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.types.Selector;
import com.ibm.wala.core.util.strings.Atom;
import com.ibm.wala.types.Descriptor;

public class MiniJar {
  private static final String USAGE =
      "MiniJar usage:\n"
          + "This tool takes the following command line options:\n"
          + "    <jarname> <jarname> ...   Process the classes from these jars\n"
          + "    -o <jarname>              Put the resulting classes into <jarname>\n";

  private static OfflineInstrumenter instrumenter;


  static class UnknownAttributeException extends Exception {
    private static final long serialVersionUID = 8845177787110364793L;

    UnknownAttributeException(String t) {
      super("Attribute '" + t + "' not understood");
    }
  }

  public static void main(String[] args) throws Exception {
    if (args == null || args.length == 0) {
      System.err.println(USAGE);
      System.exit(1);
    }

    String jarFile = "";
    Set<String> mainClasses = new HashSet<String>();
    String scopeFileData = "";
    String entryClass = "";
    String inclusionsFile = "";
    String entrypointsFile = "";

    for (int i = 0; i < args.length - 1; i++) {
      if (args[i] == null) {
        throw new IllegalArgumentException("args[" + i + "] is null");
      }
      if (!args[i].startsWith("-") && args[i].endsWith("jar")) {
          jarFile = args[i];  // Assuming a single jar is passed in
      }
      if (args[i].startsWith("-m")) {
    	  mainClasses.add(args[i+1]);
      }
      if (args[i].startsWith("-d")) {
    	  scopeFileData = args[i+1];
      }
      if (args[i].startsWith("-e")) {
    	  entryClass = args[i+1];
      }
      if (args[i].startsWith("-i")) {
        inclusionsFile = args[i+1];
      }
      if (args[i].startsWith("-p")) {
        entrypointsFile = args[i+1];
      }
    }
    
    if (jarFile == "") {
    	throw new IllegalArgumentException("No Jar file specified");
    }
    
    Set<String> entrypointMethods = null;
    if (!entrypointsFile.equals("")) {
      entrypointMethods = Sets.newHashSet(Files.readLines(new File(entrypointsFile), StandardCharsets.UTF_8));
    }

    Set<String> includedPaths = null;
    if (!inclusionsFile.equals("")) {
      includedPaths = Sets.newHashSet(Files.readLines(new File(inclusionsFile), StandardCharsets.UTF_8));
    }

    final ArrayList<ZipEntry> entries = new ArrayList<>();

    instrumenter = new OfflineInstrumenter();
    instrumenter.setManifestBuilder(entries::add);
    instrumenter.parseStandardArgs(args);
    instrumenter.beginTraversal();
    ClassInstrumenter ci;
    MiniJar cw = new MiniJar();

    String[] mClasses = new String[mainClasses.size()];
    Set<String> cg = cw.getReachableMethods(mainClasses.toArray(mClasses), scopeFileData, entryClass, entrypointMethods, includedPaths);
   
    while ((ci = instrumenter.nextClass()) != null) {
      try {
        cw.processClass(ci, cg, includedPaths);
      } catch (UnknownAttributeException ex) {
        System.err.println(ex.getMessage() + " in " + instrumenter.getLastClassResourceName());
      }
    }
    instrumenter.writeUnmodifiedClasses();
    instrumenter.close();
  }

  private void processClass(final ClassInstrumenter ci, Set<String> cg, Set<String> includedPaths) throws Exception {
    ClassReader cr = ci.getReader();
    
    ClassWriter cw =
            new ClassWriter() {
              private final Map<Object, Integer> entries = HashMapFactory.make();

              {
                ConstantPoolParser p = cr.getCP();
                for (int i = 1; i < p.getItemCount(); i++) {
                  final byte itemType = p.getItemType(i);
                  switch (itemType) {
                    case CONSTANT_Integer:
                      entries.put(p.getCPInt(i), i);
                      break;
                    case CONSTANT_Long:
                      entries.put(p.getCPLong(i), i);
                      break;
                    case CONSTANT_Float:
                      entries.put(p.getCPFloat(i), i);
                      break;
                    case CONSTANT_Double:
                      entries.put(p.getCPDouble(i), i);
                      break;
                    case CONSTANT_Utf8:
                      entries.put(p.getCPUtf8(i), i);
                      break;
                    case CONSTANT_String:
                      entries.put(new CWStringItem(p.getCPString(i), CONSTANT_String), i);
                      break;
                    case CONSTANT_Class:
                      entries.put(new CWStringItem(p.getCPClass(i), CONSTANT_Class), i);
                      break;
                    default:
                      // do nothing
                  }
                }
              }

              private int findExistingEntry(Object o) {
                return entries.getOrDefault(o, -1);
              }

              @Override
              protected int addCPEntry(Object o, int size) {
                int entry = findExistingEntry(o);
                if (entry != -1) {
                  return entry;
                } else {
                  return super.addCPEntry(o, size);
                }
              }
            };
    
    String className = cr.getName();

    ClassReader.AttrIterator iter = new ClassReader.AttrIterator();

    int methodCount = cr.getMethodCount();
    
    for (int i = 0; i < methodCount; i++) {
      cr.initMethodAttributeIterator(i, iter);
      String methodName = cr.getMethodName(i);
      String methodType = cr.getMethodType(i);

      if (!isReachable(cg, className, methodName, methodType, includedPaths)) {
    	  ci.deleteMethod(i);
      }
    }
    
    ci.emitClass(cw);
    instrumenter.outputModifiedClass(ci, cw);
  }

  private boolean isReachable(Set<String> cg, String className, String methodName, String methodType, Set<String> includedPaths) {
	  String desc = getMethodString(className, methodName, methodType);
    if (mustInclude(className, includedPaths)) {
      System.out.println("Reachable: " + desc);
      return true;
    }
	  boolean reachable = cg.contains(desc);
	  if (reachable) {
		  System.out.println("Reachable: " + desc);
	  } else {
		  System.out.println("Not reachable: " + desc);
	  }
	  return reachable;
  }

  private boolean mustInclude(String className, Set<String> includedPaths) {
    if (className.startsWith("L")) {
      className = className.substring(1);
    }
    for (String path: includedPaths) {
      if (className.startsWith(path)) {
        return true;
      }
    }
    return false;
  }
  
  private Set<Entrypoint> getEntrypoints(Set<String> methods, IClassHierarchy cha) {
    Set<Entrypoint> ret = new HashSet<Entrypoint>();
    for (String method: methods) {
      if (method.equals("")) {
        continue;
      }
      String methodName = getMethodName(method);
      //String methodDesc = getMethodDesc(method);
      String className = getMethodClassName(method);
      TypeReference typeRef = TypeReference.findOrCreate(ClassLoaderReference.Application, className);
      Selector selector = Selector.make(methodName);
      
      MethodReference metRef = MethodReference.findOrCreate(typeRef, selector);

      //MethodReference metRef = MethodReference.findOrCreate(typeRef, Atom.findOrCreateAsciiAtom(methodName), Descriptor.findOrCreateUTF8(methodDesc));

      ret.add(new DefaultEntrypoint(metRef, cha));
    }
    return ret;
  }

  private Set<String> getReachableMethods(String[] mainClasses, String scopeFileData, String entryClass, Set<String> entrypointMethods, Set<String> includedPaths) throws IOException, ClassHierarchyException, IllegalArgumentException, CallGraphBuilderCancelException {
	  //AnalysisScope scope = new Java9AnalysisScopeReader().makeJavaBinaryAnalysisScope(scopeFile, null);
	  AnalysisScope scope = new Java9AnalysisScopeReader().readJavaScope(scopeFileData, null, MiniJar.class.getClassLoader());
	
	  IClassHierarchy cha = ClassHierarchyFactory.make(scope);
	  System.out.println(cha.getNumberOfClasses() + " classes");
	  System.out.println(Warnings.asString());
	  Warnings.clear();
	  AnalysisOptions options = new AnalysisOptions();
	  Iterable<Entrypoint> entrypoints = Util.makeMainEntrypoints(scope, cha, mainClasses);
	  Set<Entrypoint> entrypointsSet = new HashSet<Entrypoint>();
	  entrypoints.forEach(e -> entrypointsSet.add(e));
    if (entrypointMethods != null) {
      entrypointsSet.addAll(getEntrypoints(entrypointMethods, cha));
    }
	  options.setEntrypoints(entrypointsSet);
    System.out.println("entrypoints:" + entrypointsSet.size());

    if (entryClass != "") {
      Iterable<Entrypoint> entrypointsP = makePublicEntrypoints(cha, entryClass);
      entrypointsP.forEach(e -> entrypointsSet.add(e));
      System.out.println("entrypointsP:" + entrypointsSet.size());
      options.setEntrypoints(entrypointsP); //Temporary overriding of main entrypoint(s) - might need to concat both iterables for main and non-main
    }

	  // you can dial down reflection handling if you like
	  options.setReflectionOptions(AnalysisOptions.ReflectionOptions.NO_FLOW_TO_CASTS);
	  AnalysisCache cache = new AnalysisCacheImpl();
	
    CallGraphBuilder<InstanceKey> builder = Util.makeRTABuilder(options, cache, cha, scope);
    //CallGraphBuilder builder = Util.makeZeroCFABuilder(Language.JAVA, options, cache, cha, scope);
    //CallGraphBuilder builder = Util.makeNCFABuilder(2, options, cache, cha, scope);
    //CallGraphBuilder builder = Util.makeVanillaNCFABuilder(2, options, cache, cha, scope);
    //CallGraphBuilder builder = Util.makeZeroOneContainerCFABuilder(options, cache, cha, scope);
	
	  System.out.println("building call graph...");
	  CallGraph cg = builder.makeCallGraph(options, null);
	  System.out.println("done! " + cg.getNumberOfNodes());
	
	  Set<String> allMethods = new HashSet<String>();
	  cg.forEach(n -> processMethod(allMethods, n, cha));
    System.out.println("number of methods: " + allMethods.size());
	
	  return allMethods;
  }

  private static void processMethod(Set<String> allMethods, CGNode n, IClassHierarchy cha) {
    IMethod m = n.getMethod();
    allMethods.add(getMethodDescriptor(m));
    allMethods.addAll(getSuperMethods(m,cha));
  }

  private static Set<String> getSuperMethods(IMethod m, IClassHierarchy cha) {
    Set<String> ret = new HashSet<String>();
    Set<IClass> superClasses = new HashSet<IClass>();
    IClass superClass = m.getDeclaringClass().getSuperclass();
    
    while(superClass != null) {
      superClasses.add(superClass);
      superClass = superClass.getSuperclass();
    }

    for(IClass klass: superClasses) {
      String superMethod = getSuperMethod(klass, getMethodName(m));
      if (superMethod != null) {
        ret.add(superMethod);
      }
    }

    for(IClass klass: m.getDeclaringClass().getAllImplementedInterfaces()){
      String superMethod = getSuperMethod(klass, getMethodName(m));
      if (superMethod != null) {
        ret.add(superMethod);
      }
    }

    return ret;
  }

  private static String getSuperMethod(IClass klass, String methodName) {
    for (IMethod m: klass.getAllMethods()) {
      String superMethodName = getMethodName(m);
      if (superMethodName.equals(methodName)) {
        return getMethodDescriptor(m);
      }
    }
    return null;
  }

  private static String getMethodString (String className, String methodName, String methodType) {
	  String ret = className + "#" + methodName + methodType;
	  if (className.startsWith("L")) {
		  return ret;
	  }
	  return "L" + ret;
  }

  private static String getMethodClassName (String name) {
    return name.substring(0, name.indexOf("#"));
  }

  private static String getMethodName (String name) {
    return name.substring(name.indexOf("#") + 1);
    //return name.substring(name.indexOf("#") + 1, name.indexOf("("));
  }

  private static String getMethodDesc (String name) {
    return name.substring(name.indexOf("("));
  }

  private static String getMethodName(IMethod m) {
    return m.getName().toString() +  m.getDescriptor().toString();
  }

  private static String getMethodDescriptor(IMethod m) {
	  return getMethodString(m.getDeclaringClass().getName().toString(), m.getName().toString(), m.getDescriptor().toString());
  }

  private static Iterable<Entrypoint> makePublicEntrypoints(
      IClassHierarchy cha, String entryClass) {
    Collection<Entrypoint> result = new ArrayList<>();
    IClass klass =
        cha.lookupClass(
            TypeReference.findOrCreate(
                ClassLoaderReference.Application,
                StringStuff.deployment2CanonicalTypeString(entryClass)));
    for (IMethod m : klass.getDeclaredMethods()) {
      if (m.isPublic()) {
        result.add(new DefaultEntrypoint(m, cha));
      }
    }
    return result;
  }

  private static Iterable<Entrypoint> makePrimordialPublicEntrypoints(IClassHierarchy cha, String entryClass) {
    final HashSet<Entrypoint> result = HashSetFactory.make();
    for (IClass clazz : cha) {

      if (clazz.getName().toString().contains(entryClass) && !clazz.isInterface() && !clazz.isAbstract()) {
        for (IMethod method : clazz.getDeclaredMethods()) {
          if (method.isPublic() && !method.isAbstract()) {
            System.out.println("Entry:" + method.getReference());
            result.add(new DefaultEntrypoint(method, cha));
          }
        }
      }
    }
    return result::iterator;
  }   

}