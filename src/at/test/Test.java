package at.test;

import java.net.URL;
import java.net.URLClassLoader;

public class Test {
	
	private void test() {
	      try {
	          ClassLoader cl = this.getClass().getClassLoader();
	          URL[] urls = ((URLClassLoader)cl).getURLs();
	          for(URL url: urls){
	            	System.out.println(url.getFile());
	            }
	      } catch (Exception e) {
	    	  e.printStackTrace();
	      }
	      
	      try {
		      Class<?> clazz = Class.forName("org.flywaydb.core.Flyway");
	      } catch (Exception e) {
	    	  e.printStackTrace();
	      }

	}

	public static void main(String[] args) {
		Test test = new Test();
		test.test();
		System.out.println("Hello World!");
	}

}
