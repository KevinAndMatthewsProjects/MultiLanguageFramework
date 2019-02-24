package languageServer;
/**
 * 
 */


import java.awt.Point;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Vector;


import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

/**
 * 
 */
public class LanguageServer {

	private static LanguageServer server = null;

	private int port = 8000;
	private boolean shouldRun = true;
	private int langId = 0;

	private JSONParser parser;

	private ArrayList<LanguageHandler> langs; // List of all language connections
	private HashMap<String, Integer> langMap; // Maps each element(class, method, etc) to the language implements it
	private HashMap<String, Object> dataMap; // Maps each class to its data
	private HashMap<Integer, Integer> returnMap; //Maps each return ID to the language thats expecting a return value
	private int returnId = 1;
	
	public LanguageServer() {
		langs = new ArrayList<>();
		langMap = new HashMap<>();
		dataMap = new HashMap<>();
		returnMap = new HashMap<>();
		parser = new JSONParser();
	}

	public static LanguageServer getInstance() {
		if (server == null) {
			server = new LanguageServer();
			return server;
		} else {
			return server;
		}
	}

	public void run() {
		try (ServerSocket socket = new ServerSocket(port)) {
			while (shouldRun) {
				Socket s = socket.accept();
				System.out.println("Connected to " + s.getInetAddress().toString());
				LanguageHandler handler = new LanguageHandler(langId++, this, s);
				langs.add(handler);
				Thread t = new Thread(handler);
				t.start();
			}
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			for (LanguageHandler handler : langs) {
				handler.stop();
			}
		}
	}

	public void registerClass(int id, String classData) throws ParseException {
		System.out.println("Registering class for lang number " + id + " with data " + classData);
		JSONObject classObj = (JSONObject) parser.parse(classData);
		langMap.put((String) classObj.get("name"), id);
		dataMap.put((String) classObj.get("name"), classObj);

	}

	public void createObject(int id, String jsonObjdata) throws InvalidObjectException, ParseException {
		System.out.println("Creating an object from lang " + id + " with data: " + jsonObjdata);
		JSONObject objData = (JSONObject) parser.parse(jsonObjdata);
		String className = (String) objData.get("name");
		JSONArray args = (JSONArray) objData.get("arguments"); //JsonObject (value, type)
		
		Object langNumCheck = langMap.get(className);
		if (langNumCheck == null) {
			throw new InvalidObjectException("Could not find class" + className);
		}
		int langNum = (Integer) langNumCheck;
		boolean isStrict = langs.get(langNum).isStrictTypes();
		JSONObject classData = (JSONObject) dataMap.get(className);
		JSONArray constructors = (JSONArray) classData.get("constructors");
		int constructorIndex = matchParams(constructors, args, isStrict);
		
		if(constructorIndex == -1) {
			throw new InvalidObjectException("Could not find matching paramaters for " + args.toJSONString() + ". Types are: " + constructors.toJSONString());
		} else {
			JSONArray constructor = (JSONArray) constructors.get(constructorIndex);
			String varName = (String) objData.get("varName");
			langMap.put("var_" + varName, langNum);
			dataMap.put("var_" + varName, className);
			langs.get(langNum).createObject(className, varName, args);
		}
	}
	
	public void callMethod(int id, String jsonMethodData) throws ParseException, InvalidObjectException {
		System.out.println("Calling method for lang number " + id + " with data " + jsonMethodData);
		JSONObject methodData = (JSONObject) parser.parse(jsonMethodData);
		String varName = "var_" + ((String) methodData.get("varName"));
		String className = (String) dataMap.get(varName);
		JSONObject classData = (JSONObject) dataMap.get(className);
		String methodName = (String) methodData.get("name");
		JSONArray possibleParamaters = new JSONArray();
		JSONArray allMethods = (JSONArray) classData.get("methods");
		Iterator<JSONObject> i =  allMethods.iterator();
		while(i.hasNext()) {
			JSONObject method = i.next();
			if(method.get("name").equals(methodName)) {
				possibleParamaters.add((JSONArray)method.get("parameters"));
			}
		}
		JSONArray args = (JSONArray) methodData.get("arguments");

		
		System.out.println(possibleParamaters.toJSONString());
		System.out.println(args.toJSONString());
		
		int langNum = langMap.get(varName);
		int methodIndex =  matchParams(possibleParamaters, args, langs.get(langNum).isStrictTypes());
		if(methodIndex == -1) {
			throw new InvalidObjectException("Could not find matching paramaters for " + args.toJSONString() + ". Types are: " + possibleParamaters.toJSONString());
		}
		String returnType = (String) ((JSONObject)allMethods.get(methodIndex)).get("return");
		
		System.out.println(returnType);
		
		if (!returnType.equals("void")) {
			returnMap.put(returnId, id);
			langs.get(langNum).callMethod((String) methodData.get("varName"),  methodName, returnId++, args);
		} else {
			langs.get(langNum).callMethod((String) methodData.get("varName"),  methodName, 0, args);
		}
		
	}
	
	public void returnValue(int id, String jsonReturnData) throws ParseException {
		System.out.println("Returning a value from lang " + id + " with data " + jsonReturnData );
		JSONObject returnObj = (JSONObject) parser.parse(jsonReturnData);
		int returnID = Integer.valueOf(returnObj.get("returnID").toString());
		String returnType = (String) returnObj.get("returnType");
		String returnVal = (String) returnObj.get("returnVal");
		
		langs.get(returnMap.get(returnID)).returnValue(returnVal, returnType);
		
	}
	
	private int matchParams(JSONArray possibleParamaters, JSONArray arguements, boolean isStrict) {
		boolean found = false;
		JSONArray checkParamater = null;
		System.out.println("Checking paramaters with possibilities " + possibleParamaters.toJSONString() + " with args" + arguements.toJSONString());
		int i;
		for (i = 0; i < possibleParamaters.size(); i++) {
			checkParamater = (JSONArray) possibleParamaters.get(i);
			if (isStrict) {
				if (checkParamater.size() == arguements.size()) {
					boolean matches = true;
					for (int j = 0; j < checkParamater.size(); j++) {
						Object type = (((JSONObject)arguements.get(j)).values().toArray()[0]);
						if (!checkParamater.get(j).equals("any") && !type.equals(checkParamater.get(j))) {
							System.out.println("type " + type + " does not match " + checkParamater.get(j));
							matches = false;
							break;
						}
					}
					if (matches) {
						found = true;
						break;
					}
				}
			} else {
				if (checkParamater.size() == arguements.size()) {
					found = true;
					break;
				}
			}
		}
		if(!found) {
			return -1;
		} else {
			return i;
		}
	}

	public class InvalidObjectException extends Exception {
		public InvalidObjectException(String msg) {
			super(msg);
		}
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public void stop() {
		shouldRun = false;
	}



}
