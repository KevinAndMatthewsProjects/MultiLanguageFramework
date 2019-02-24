package languageClient;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

@SuppressWarnings("unchecked")
public class LanguageClient implements Runnable {

	private String host;
	private int port;

	private JSONParser parser;

	private HashMap<String, Object> globalLocalObjects; // Objects that other languages have created that are stored
														// locally

	private LinkedBlockingQueue<String> outgoing;
	private SynchronousQueue<JSONObject> returnQue; //Blocks for return values;

	public LanguageClient(String host, int port) {
		this.host = host;
		this.port = port;
		outgoing = new LinkedBlockingQueue<>();
		globalLocalObjects = new HashMap<>();
		returnQue = new SynchronousQueue<>();
		parser = new JSONParser();
		Thread t = new Thread(this);
		t.start();
	}

	@Override
	public void run() {
		Socket s = null;
		try {
			s = new Socket(host, port);
		} catch (IOException e) {
			e.printStackTrace();
		}
		try (PrintWriter out = new PrintWriter(s.getOutputStream(), true);
				BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()))) {

			// Init stuff
			JSONObject langData = new JSONObject();
			langData.put("strictTypes", true);
			writeCommand(LanguageOperations.Init, langData.toJSONString());

			while (true) {
				try {
					if (in.ready()) {
						String input = in.readLine();
						String action = input.substring(0, 5);
						int returnID = Integer.parseInt(input.substring(5, 10));
						String data = input.substring(10);
						if (action.equals(String.format("%05d", LanguageOperations.CreateObject.ordinal()))) {
							createObjectRemote(data);
						} else if (action.equals(String.format("%05d", LanguageOperations.Error.ordinal()))) {
							System.err.println("Error: " + data);
						} else if (action.equals(String.format("%05d", LanguageOperations.CallMethod.ordinal()))) {
							callMethodRemote(data, returnID);
						} else if (action.equals(String.format("%05d", LanguageOperations.Return.ordinal()))) {
							returnValue(data);
						} else{
							System.out.println("Unknown action: " + action);
						}
					}

					if (!outgoing.isEmpty()) {
						out.println(outgoing.take());
					}
				} catch (ParseException | ClassNotFoundException e) {
					e.printStackTrace();
				}
			}

		} catch (IOException | InterruptedException e) {
			e.printStackTrace();
		}
	}



	public void registerClass(Class c) {

		JSONObject classObj = new JSONObject();
		classObj.put("name", c.getName());
		JSONArray methods = new JSONArray();
		for (Method m : c.getMethods()) {
			if (m.isAnnotationPresent(Global.class)) {
				JSONObject methodObj = new JSONObject();
				methodObj.put("name", m.getName());
				JSONArray paramArr = new JSONArray();
				for (Parameter p : m.getParameters()) {
					Class<?> paramType = p.getType();
					if (!validClass(paramType)) {
						throw new RuntimeException("Cannot use type " + paramType.getName());
					}
					paramArr.add(standardize(paramType.getName()));

				}
				methodObj.put("parameters", paramArr);
				methodObj.put("return", m.getReturnType().getName());
				methods.add(methodObj);
			}
		}
		classObj.put("methods", methods);

		JSONArray constructors = new JSONArray();
		for (Constructor<?> constructor : c.getConstructors()) {
			if (constructor.isAnnotationPresent(Global.class)) {
				JSONArray paramArr = new JSONArray();
				for (Parameter p : constructor.getParameters()) {
					Class<?> paramType = p.getType();
					if (!validClass(paramType)) {
						throw new RuntimeException("Cannot use type " + paramType.getName());
					}
					paramArr.add(standardize(paramType.getName()));

				}
				constructors.add(paramArr);
			}
		}

		if (constructors.isEmpty()) {
			System.err.println("Object must have atleast one global constructor");
			System.exit(1);
		}
		classObj.put("constructors", constructors);

		writeCommand(LanguageOperations.RegisterClass, classObj.toJSONString());
	}

	public void createObject(String className, String varName, Object... params) throws InvalidObjectException {
		JSONArray paramsArr = new JSONArray();
		for (Object o : params) {
			if (!isValidObject(o)) {
				throw new InvalidObjectException("Object " + o.getClass().getName() + " is not valid");
			}
			JSONObject param = new JSONObject();
			param.put(o.toString(), standardize(o.getClass().getName()));
			paramsArr.add(param);
		}
		JSONObject objData = new JSONObject();
		objData.put("name", className);
		objData.put("arguments", paramsArr);
		objData.put("varName", varName);
		writeCommand(LanguageOperations.CreateObject, objData.toJSONString());
	}
	

	private void callMethodRemote(String jsonData, int returnID) throws ParseException, ClassNotFoundException {
		System.out.println("Calling method with " + jsonData);
		JSONObject objData = (JSONObject) parser.parse(jsonData);
		String methodName = (String) objData.get("name");
		String varName = (String) objData.get("varName");
		JSONArray jsonArgs = (JSONArray) objData.get("arguments");
		Object[] args = new Object[jsonArgs.size()];
		Class[] types = new Class[jsonArgs.size()];
		for (int i = 0; i < args.length; i++) {
			JSONObject arg = (JSONObject) jsonArgs.get(i);
			types[i] = Class.forName(unstandardize(arg.values().toArray()[0].toString()));
			args[i] = box(arg.keySet().toArray()[0].toString(), types[i].getName());
		}
		try {
			Object obj = globalLocalObjects.get(varName);
			Object returnVal = obj.getClass().getMethod(methodName, types).invoke(obj, args);
			if(returnID != 0) {
				JSONObject returnObj = new JSONObject();
				returnObj.put("returnID", returnID);
				returnObj.put("returnVal", returnVal.toString());
				returnObj.put("returnType", standardize(returnVal.getClass().getName()));
				writeCommand(LanguageOperations.Return, returnObj.toJSONString());
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public Object callMethod(String varName, String methodName, boolean hasReturnVal, Object... args) throws InvalidObjectException, InterruptedException, ClassNotFoundException {
		JSONArray paramsArr = new JSONArray();
		for (Object o : args) {
			if (!isValidObject(o)) {
				throw new InvalidObjectException("Object " + o.getClass().getName() + " is not valid");
			}
			JSONObject param = new JSONObject();
			param.put(o.toString(), standardize(o.getClass().getName()));
			paramsArr.add(param);
		}
		JSONObject objData = new JSONObject();
		objData.put("name", methodName);
		objData.put("arguments", paramsArr);
		objData.put("varName", varName);
		writeCommand(LanguageOperations.CallMethod, objData.toJSONString());
		if(hasReturnVal) {
			System.out.println("Waiting on return q");
			JSONObject returnVal = returnQue.take();
			String returnType = (String) returnVal.get("returnType");
			Class returnClass = Class.forName(unstandardize(returnType));
			return box(returnVal.get("returnVal").toString(), returnClass.getName());
		} else {
			return null;
		}
	}
	
	public void returnValue(String jsonReturnData) throws InterruptedException, ParseException {
		System.out.println("Inserting into ret q");
		returnQue.put((JSONObject) parser.parse(jsonReturnData));
	}

	private void createObjectRemote(String jsonData) throws ParseException, ClassNotFoundException {
		System.out.println("Creating global local object with data " + jsonData);
		JSONObject objData = (JSONObject) parser.parse(jsonData);
		String name = (String) objData.get("name");
		String varName = (String) objData.get("varName");
		JSONArray jsonArgs = (JSONArray) objData.get("arguments");
		Object[] args = new Object[jsonArgs.size()];
		Class[] types = new Class[jsonArgs.size()];
		for (int i = 0; i < args.length; i++) {
			JSONObject arg = (JSONObject) jsonArgs.get(i);
			types[i] = Class.forName(unstandardize(arg.values().toArray()[0].toString()));
			args[i] = box(arg.keySet().toArray()[0].toString(), types[i].getName());
		}
		try {
			Object obj = Class.forName(name).getConstructor(types).newInstance(args);
			globalLocalObjects.put(varName, obj);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public void writeCommand(LanguageOperations op, String str) {
		outgoing.add(String.format(String.format("%05d", op.ordinal()) + str));
	}
	
	private String standardize(String s) {
		switch (s) {
		case "java.lang.Integer":
			return "int";
		case "java.lang.String":
			return "string";

		}
		System.out.println("No type override for " + s);
		return s;
	}

	private String unstandardize(String s) {
		switch (s) {
		case "string":
			return "java.lang.String";
		case "int":
			return "java.lang.Integer";

		}
		System.out.println("No type override for " + s);
		return s;
	}

	private Object box(String value, String type) {
		try {
			if (isWrapperType(Class.forName(type))) {
				switch (type) {
				case "java.lang.Boolean":
					return Boolean.valueOf(value);
				case "java.lang.Character":
					return Character.valueOf(value.charAt(0));
				case "java.lang.Byte":
					return Byte.valueOf(value);
				case "java.lang.Short":
					return Short.valueOf(value);
				case "java.lang.Integer":
					return Integer.valueOf(value);
				case "java.lang.Long":
					return Long.valueOf(value);
				case "java.lang.Float":
					return Float.valueOf(value);
				case "java.lang.Double":
					return Double.valueOf(value);

				default:
					return value;
				}
			}
		} catch (NumberFormatException | ClassNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return value;
	}

	private boolean isValidObject(Object o) {
		return true;
	}

	private boolean validClass(Class<?> paramType) {
		return true;
	}

	public class InvalidObjectException extends Exception {

		private static final long serialVersionUID = -2502940211386728316L;

		public InvalidObjectException(String msg) {
			super(msg);
		}
	}

	private static final Set<Class<?>> WRAPPER_TYPES = getWrapperTypes();

	public static boolean isWrapperType(Class<?> c) {
		return WRAPPER_TYPES.contains(c);
	}

	private static Set<Class<?>> getWrapperTypes() {
		Set<Class<?>> ret = new HashSet<Class<?>>();
		ret.add(Boolean.class);
		ret.add(Character.class);
		ret.add(Byte.class);
		ret.add(Short.class);
		ret.add(Integer.class);
		ret.add(Long.class);
		ret.add(Float.class);
		ret.add(Double.class);
		ret.add(Void.class);
		return ret;
	}

}
