package minijar;

import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import java.util.Collection;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import java.util.ArrayList;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.core.util.strings.StringStuff;
import com.ibm.wala.ipa.callgraph.impl.DefaultEntrypoint;
import java.util.HashSet;
import com.ibm.wala.util.collections.HashSetFactory;

public class EntrypointUtil {
    public static Iterable<Entrypoint> makePublicEntrypoints(IClassHierarchy cha, String entryClass) {
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

  public static Iterable<Entrypoint> makePrimordialPublicEntrypoints(IClassHierarchy cha, String entryClass) {
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
