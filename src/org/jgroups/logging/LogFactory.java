package org.jgroups.logging;


/**
 * Factory that creates {@link Log} instances.
 *
 * @author Manik Surtani
 * @since 4.0
 */
public class LogFactory {

   public static final boolean IS_LOG4J_AVAILABLE;

   static {
      boolean available;
      try {
         Class.forName("org.apache.log4j.Logger");
         available = true;
      }
      catch (ClassNotFoundException cnfe) {
         available = false;
      }
      IS_LOG4J_AVAILABLE = available;
   }

   public static Log getLog(Class clazz) {
       if (IS_LOG4J_AVAILABLE) {
           return new Log4JLogImpl(clazz);
       } else {
           return new JDKLogImpl(clazz);
       }
   }

   public static Log getLog(String category) {
      if (IS_LOG4J_AVAILABLE) {
         return new Log4JLogImpl(category);
      } else {
         return new JDKLogImpl(category);
      }
   }
}