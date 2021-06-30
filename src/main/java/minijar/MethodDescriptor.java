package minijar;

import java.util.Set;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.types.Selector;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.DefaultEntrypoint;
import com.ibm.wala.ipa.cha.IClassHierarchy;

public class MethodDescriptor {
    String className;
    String methodName;
    String methodType;


    public MethodDescriptor(String className, String methodName, String methodType) {
        this.className = className;
        this.methodName = methodName;
        this.methodType = methodType;
    }

    public MethodDescriptor(String descriptor) {
        className = descriptor.substring(0, descriptor.indexOf("#"));
        methodName = descriptor.substring(descriptor.indexOf("#") + 1, descriptor.indexOf(")") + 1);
        methodType = descriptor.substring(descriptor.indexOf(")") + 1);
    }

    public MethodDescriptor(IMethod method) {
        className = method.getDeclaringClass().getName().toString();
        methodName = method.getName().toString();
        methodType = method.getDescriptor().toString();
    }

    public String getClassName() {
        return className;
    }

    public String getName() {
        return methodName;
    }

    public String getType() {
        return methodType;
    }

    public String getNameType() {
        return methodName + methodType;
    }

    public MethodReference getMethodReference() {
        TypeReference typeRef = TypeReference.findOrCreate(ClassLoaderReference.Application, className);
        Selector selector = Selector.make(getNameType());
        return MethodReference.findOrCreate(typeRef, selector);
      }


    public Entrypoint getEntrypoint(IClassHierarchy cha) {
        MethodReference metRef = getMethodReference();
        return new DefaultEntrypoint(metRef, cha);
    }


    public boolean isReachable(Set<String> cg, Set<String> includedPaths) {
        String desc = toString();
  
        // If this method is a static initializer for a class, just include it for now
        if (desc.contains("$static$")) {
            System.out.println("Reachable because static initializer: " + desc);
            return true;
        }
  
        if (includedPaths != null) {
            if (mustInclude(className, includedPaths)) {
                System.out.println("Reachable by inclusion: " + desc);
                return true;
            }
        }   
        boolean reachable = cg.contains(desc);
        if (reachable) {
            System.out.println("Reachable by cg: " + desc);
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
        if (path.equals("")) {
          continue;
        }
        if (className.startsWith(path)) {
          return true;
        }
      }
      return false;
    }


    public String toString() {
        String ret = className + "#" + methodName + methodType;
        if (className.startsWith("L")) {
            return ret;
        }
        return "L" + ret;
    }


}
