package at.test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ReadAttributes {
	public static void main(String[] args) throws IOException {
		// Get a Path object
		Path path = Paths.get("D:\\BMI\\Liberty\\OpenLiberty22\\SBS\\config\\si3sbs.properties");

		// Prepare the attribute list
		String attribList = "basic:size,lastModifiedTime";

		// Read the attributes
//		Map<String, Object> attribs = Files.readAttributes(path, attribList);
		Map<String, Object> attribs = Files.readAttributes(path, "*");
		attribs.forEach((string, object) -> {
			System.out.println("Attributes: " + string + ": " + object);
		});
		if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
			System.out.println("isDirectory: true");
		}
//		LinkOption[] a = new LinkOption[] { LinkOption.NOFOLLOW_LINKS };
		System.out.println(ReadAttributes.isSet(path, "isDirectory", (Object)new LinkOption[] { LinkOption.NOFOLLOW_LINKS}));
		System.out.println(ReadAttributes.isSet(path, "isExecutable"));
		System.out.println(ReadAttributes.isSet(path, "isHidden"));
		System.out.println(ReadAttributes.isSet(path, "isReadable"));
		System.out.println(ReadAttributes.isSet(path, "isRegularFile", (Object)new LinkOption[] { LinkOption.NOFOLLOW_LINKS}));
		System.out.println(ReadAttributes.isSet(path, "isSymbolicLink"));
		System.out.println(ReadAttributes.isSet(path, "isWritable"));
		
		// Display the attributes on the standard output
		System.out.println(attribs.get("size"));
		System.out.println(attribs.get("lastModifiedTime"));

	}

	private static String isSet(final Path path, final String attribute, final Object... additionalArguments) {
		String name = attribute;
//		Method[] methods = Files.class.getMethods();
		try {
			List<Class<?>> parameterTypes = new ArrayList<>();
			parameterTypes.add(Path.class);
			if (additionalArguments != null) {
				for (Object additionalArgument : additionalArguments) {
					parameterTypes.add(additionalArgument.getClass());
				}
			}
			Method method = Files.class.getMethod(name, parameterTypes.toArray(new Class<?>[0]));
			
			List<Object> args = new ArrayList<>();
			args.add(path);
			if (additionalArguments != null) {
				for (Object additionalArgument : additionalArguments) {
					args.add(additionalArgument);
				}
			}			
			Object result = method.invoke(null, args.toArray(new Object[0]));
			if (result instanceof Boolean) {
				return name + ": " + (Boolean) result;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return name + ": " + null;
	}
}