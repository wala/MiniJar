package minijar;

import java.util.Set;
import java.util.HashSet;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.cha.IClassHierarchy;

public class MethodUtil {
    public static Set<String> getSuperMethods(IMethod m, IClassHierarchy cha) {
        Set<String> ret = new HashSet<String>();
        Set<IClass> superClasses = new HashSet<IClass>();
        IClass superClass = m.getDeclaringClass().getSuperclass();
        
        while(superClass != null) {
          superClasses.add(superClass);
          superClass = superClass.getSuperclass();
        }
    
        MethodDescriptor desc = new MethodDescriptor(m);
    
        for(IClass klass: superClasses) {
          String superMethod = getMethod(klass, desc.getNameType());
          if (superMethod != null) {
            ret.add(superMethod);
          }
        }
    
        for(IClass klass: m.getDeclaringClass().getAllImplementedInterfaces()){
          String superMethod = getMethod(klass, desc.getNameType());
          if (superMethod != null) {
            ret.add(superMethod);
          }
        }
    
        return ret;
      }
    
      public static String getMethod(IClass klass, String methodName) {
        for (IMethod m: klass.getAllMethods()) {
          MethodDescriptor desc = new MethodDescriptor(m);
          String superMethodName = desc.getNameType();
          if (superMethodName.equals(methodName)) {
            return desc.toString();
          }
        }
        return null;
      }
}
