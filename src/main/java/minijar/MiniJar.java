/*
 * Copyright (c) 2021 IBM Corporation.
 * All rights reserved. 
 * 
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     IBM Corporation - initial implementation
 */
package minijar;

import java.io.*;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.ibm.wala.analysis.reflection.ClassFactoryContextInterpreter;
import com.ibm.wala.analysis.reflection.ClassFactoryContextSelector;
import com.ibm.wala.analysis.reflection.JavaTypeContext;
import com.ibm.wala.classLoader.*;
import com.ibm.wala.classLoader.Module;
import com.ibm.wala.core.java11.Java9AnalysisScopeReader;
import com.ibm.wala.core.util.io.FileProvider;
import com.ibm.wala.core.util.strings.Atom;
import com.ibm.wala.core.util.warnings.Warnings;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.impl.ContextInsensitiveSelector;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PropagationCallGraphBuilder;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.shrike.shrikeBT.shrikeCT.ClassInstrumenter;
import com.ibm.wala.shrike.shrikeBT.shrikeCT.OfflineInstrumenter;
import com.ibm.wala.shrike.shrikeCT.ClassReader;
import com.ibm.wala.shrike.shrikeCT.ClassWriter;
import com.ibm.wala.shrike.shrikeCT.ConstantPoolParser;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.config.FileOfClasses;
import com.ibm.wala.util.intset.IntSet;

public class MiniJar {
  private static final String USAGE =
      "MiniJar usage:\n"
          + "This tool takes the following command line options:\n"
          + "    -m <mainClass>            Provide one or more mainClasses\n"
          + "    -d <scopeDataFile>        Provide a scope data file\n"  
          + "    -p <entryPointsFile>      Provide an entrypoint file\n"
          + "    -i <inclusionsFile>       Provide an inclusion file\n"
          + "    -o <outputJarName>        Put the resulting classes into <outputJarName>\n";

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

    String inJarFile = "";
    String outJarFile = "";
    Set<String> mainClasses = new HashSet<String>();
    String scopeFileData = "";
    String entryClass = "";
    String inclusionsFile = "";
    String entrypointsFile = "";

    for (int i = 0; i < args.length - 1; i++) {
      if (args[i] == null) {
        throw new IllegalArgumentException("args[" + i + "] is null");
      }
      if (args[i].equals("-o") && i + 1 < args.length) {
          outJarFile = args[i+1];
          i = i + 2;
      } else if (!args[i].startsWith("-") && args[i].endsWith("jar")) {
          inJarFile = args[i];  // Assuming a single jar is passed in
      } else if (args[i].equals("-m")) {
    	mainClasses.add(args[i+1]);
      } else if (args[i].equals("-d")) {
    	scopeFileData = args[i+1];
      } else if (args[i].equals("-i")) {
        inclusionsFile = args[i+1];
      } else if (args[i].equals("-p")) {
        entrypointsFile = args[i+1];
      }
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

      if (inJarFile == "") { //read input jar file from scope file data
          String line;

          File scopeFile = new File(scopeFileData);
          if (scopeFile.exists()) {
              BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(scopeFile), "UTF-8"));
              while ((line = r.readLine()) != null) {
                  StringTokenizer toks = new StringTokenizer(line, "\n,");
                  if (!toks.hasMoreTokens()) {
                      return;
                  }
                  Atom loaderName = Atom.findOrCreateUnicodeAtom(toks.nextToken());
                  String language = toks.nextToken();
                  String entryType = toks.nextToken();
                  String entryPathname = toks.nextToken();
                  if (loaderName.equals(ClassLoaderReference.Application.getName())
                          && language.equals(Language.JAVA.toString())
                          && "jarFile".equals(entryType)
                          && entryPathname.endsWith("jar")) {
                      URL url = MiniJar.class.getClassLoader().getResource(entryPathname);
                      inJarFile = new File(URLDecoder.decode(url.getPath(), "UTF-8")).toURI().getPath();;
                  }
              }
          }
          instrumenter.addInputJar(new File(inJarFile));
      }

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

      if (!new MethodDescriptor(className, methodName, methodType).isReachable(cg, includedPaths)) {
    	  ci.deleteMethod(i);
      }
    }
    
    ci.emitClass(cw);
    instrumenter.outputModifiedClass(ci, cw);
  }
  
  private Set<Entrypoint> getEntrypoints(Set<String> methods, IClassHierarchy cha) {
    Set<Entrypoint> ret = new HashSet<Entrypoint>();
    for (String method: methods) {
      if (method.equals("")) {
        continue;
      }
      ret.add(new MethodDescriptor(method).getEntrypoint(cha));
    }
    return ret;
  }

  private Set<String> getReachableMethods(String[] mainClasses, String scopeFileData, String entryClass, Set<String> entrypointMethods, Set<String> includedPaths) throws IOException, ClassHierarchyException, IllegalArgumentException, CallGraphBuilderCancelException {
	  AnalysisScope scope = new Java9AnalysisScopeReader().readJavaScope(scopeFileData, null, MiniJar.class.getClassLoader());
	  addDefaultExclusions(scope);
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

	  // you can dial down reflection handling if you like
	  options.setReflectionOptions(AnalysisOptions.ReflectionOptions.NO_FLOW_TO_CASTS);

	  AnalysisCache cache = new AnalysisCacheImpl();
	  CallGraphBuilder builder = Util.makeZeroCFABuilder(Language.JAVA, options, cache, cha, scope);

	  options.setSelector(new ClassTargetSelector() {
		  ClassTargetSelector base = options.getClassTargetSelector();
		  Set<IClass> seen = new HashSet<>();
		  FileWriter log = new FileWriter("seenNOFLOWClasses.txt");

		  @Override
		  public IClass getAllocatedTarget(CGNode caller, NewSiteReference site) {
			  IClass baseC = base.getAllocatedTarget(caller, site);

			  if (!seen.contains(baseC)) {
				  seen.add(baseC);
				  try {
				        log.write("**** SEEN: " + baseC + "\n");
				        log.flush();
				  } catch (IOException e) {
				  	System.err.println(e);
				  }

			  }
			  return baseC;
		  }
	  });

	  PropagationCallGraphBuilder propBuilder = ((PropagationCallGraphBuilder) builder);
	  propBuilder.setContextSelector(new ContextSelector() {
	  	  ContextSelector base = propBuilder.getContextSelector();
	  	  ClassFactoryContextSelector fac = new ClassFactoryContextSelector();
		  FileWriter log = new FileWriter("seenReflectiveClasses.txt");

		  @Override
		  public Context getCalleeTarget(CGNode caller, CallSiteReference site, IMethod callee, InstanceKey[] actualParameters) {
		  	Context con = fac.getCalleeTarget(caller, site, callee, actualParameters);
		  	if (con instanceof JavaTypeContext) {
		  		JavaTypeContext javaCon  = (JavaTypeContext) con;
		  		try {
		  		        log.write(javaCon.getType().getType().toString() + "\n");
		  		        log.flush();
		  		} catch (IOException e) {
				        System.err.println(e);
			        }
		        }
		  	return base.getCalleeTarget(caller, site, callee, actualParameters);
		  }

		  @Override
		  public IntSet getRelevantParameters(CGNode caller, CallSiteReference site) {
			  return base.getRelevantParameters(caller, site);
		  }
	  });


	  System.out.println("building call graph...");
	  CallGraph cg = builder.makeCallGraph(options, null);
	  System.out.println("done! " + cg.getNumberOfNodes());
	  Set<String> allMethods = new HashSet<String>();
	  cg.forEach(n -> processMethod(allMethods, n, cha, cg));
	  System.out.println("number of methods: " + allMethods.size());

	  System.out.println("*** Call graph ***");
	  cg.forEach(n -> System.out.println(new MethodDescriptor(n.getMethod()).toString()));
	  System.out.println("*** End - Call graph ***");

	  return allMethods;
  }

  private static void processMethod(Set<String> allMethods, CGNode n, IClassHierarchy cha, CallGraph cg) {
    IMethod m = n.getMethod();
    String desc = new MethodDescriptor(m).toString();
    allMethods.add(desc);
    allMethods.addAll(MethodUtil.getSuperMethods(m,cha));

    // Process for call sites
    n.iterateCallSites().forEachRemaining(call -> {
      if (call.getDeclaredTarget().equals(MethodReference.JavaLangClassNewInstance)) {
        Set<CGNode> targets = cg.getPossibleTargets(n, call);
        if (targets.isEmpty()) {
          System.out.println(" BOGUS New Instance, node: " + n + " call: " + call);
        }
        if (targets.size() == 1 && targets.iterator().next().getContext().equals(Everywhere.EVERYWHERE)) {
        	System.out.println(" BOGUS New Instance with generic target, node: " + n + " call: " + call);
        }
      }
      
      if (call.getDeclaredTarget().equals(MethodReference.JavaLangClassForName)) {
          Set<CGNode> targets = cg.getPossibleTargets(n, call);
          if (targets.isEmpty()) {
            System.out.println(" BOGUS For Name, node: " + n + " call: " + call);
          }
          if (targets.size() == 1 && targets.iterator().next().getContext().equals(Everywhere.EVERYWHERE)) {
          	System.out.println(" BOGUS For Name with generic target, node: " + n + " call: " + call);
          }
        }
    });
  }

	private static final String EXCLUSIONS = "java/awt/.*\n" +
		"javax/swing/.*\n" +
		"sun/awt/.*\n" +
		"sun/swing/.*\n" +
		"com/sun/.*\n" +
		"sun/.*\n" +
		"org/netbeans/.*\n" +
		"org/openide/.*\n" +
		"com/ibm/crypto/.*\n" +
		"com/ibm/security/.*\n" +
		"org/apache/xerces/.*\n" +
		"java/security/.*\n" +
		"jdk/.*\n" +
		"";

	public static void addDefaultExclusions(AnalysisScope scope) throws UnsupportedEncodingException, IOException {
		scope.setExclusions(new FileOfClasses(new ByteArrayInputStream(EXCLUSIONS.getBytes("UTF-8"))));
	}

}