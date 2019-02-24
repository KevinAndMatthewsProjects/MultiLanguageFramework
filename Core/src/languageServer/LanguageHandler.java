package languageServer;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import languageServer.LanguageServer.InvalidObjectException;

public class LanguageHandler implements Runnable {

	private LanguageServer server;
	private Socket socket;
	private boolean shouldRun = true;
	private int id;

	private boolean strictTypes = true;

	private LinkedBlockingQueue<String> outgoing; // Synchronizes outgoing messages

	public LanguageHandler(int id, LanguageServer server, Socket s) {
		this.server = server;
		this.socket = s;
		this.id = id;
		outgoing = new LinkedBlockingQueue<>();
	}

	@Override
	public void run() {
		try (PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
				BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
			while (shouldRun) {
				if (in.ready()) {
					String input = in.readLine();
					System.out.println("Read " + input);
					String action = input.substring(0, 5);
					String data = input.substring(5);
					if (action.equals(String.format("%05d", LanguageOperations.RegisterClass.ordinal()))) {
						server.registerClass(id, data);
					} else if (action.equals(String.format("%05d", LanguageOperations.CreateObject.ordinal()))) {
						server.createObject(id, data);
					} else if (action.equals(String.format("%05d", LanguageOperations.Init.ordinal()))) {
						init(data);
					} else if (action.equals(String.format("%05d", LanguageOperations.CallMethod.ordinal()))) {
						server.callMethod(id, data);
					} else if (action.equals(String.format("%05d", LanguageOperations.Return.ordinal()))) {
						server.returnValue(id, data);
					} else  {
						System.out.println("Unknown action: " + action);
					}
				}
				if (!outgoing.isEmpty()) {
					String message = outgoing.take();
					System.out.println("Writing out: " + message);
					out.println(message);
				}

			}
		} catch (IOException e) {
			e.printStackTrace();
		} catch (ParseException e) {
			System.err.println("Error parsing JSON");
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InvalidObjectException e) {
			e.printStackTrace();
			writeCommand(LanguageOperations.Error, e.getMessage());
		}

	}

	public void init(String jsonData) throws ParseException {
		JSONObject langData = (JSONObject) (new JSONParser()).parse(jsonData);
		strictTypes = (boolean) langData.get("strictTypes");
	}

	@SuppressWarnings("unchecked")
	public void createObject(String className, String objName, JSONArray params) {
		JSONObject objData = new JSONObject();
		objData.put("name", className);
		objData.put("arguments", params);
		objData.put("varName", objName);
		writeCommand(LanguageOperations.CreateObject, objData.toJSONString());
	}
	
	public void callMethod(String varName, String methodName, int returnId, JSONArray args) {
		JSONObject objData = new JSONObject();
		objData.put("name", methodName);
		objData.put("arguments", args);
		objData.put("varName", varName);
		writeCommand(LanguageOperations.CallMethod, returnId, objData.toJSONString());
	}
	
	public void returnValue(String returnValue, String returnType) {
		JSONObject retData = new JSONObject();
		retData.put("returnVal", returnValue);
		retData.put("returnType", returnType);
		writeCommand(LanguageOperations.Return, retData.toJSONString());
	}

	public void stop() {
		shouldRun = false;
	}

	public boolean isStrictTypes() {
		return strictTypes;
	}

	public void setStrictTypes(boolean strictTypes) {
		this.strictTypes = strictTypes;
	}

	public void writeCommand(LanguageOperations op, String str) {
		writeCommand(op, 0, str);
	}
	
	public void writeCommand(LanguageOperations op, int returnId,  String str) {
		outgoing.add(String.format(String.format("%05d", op.ordinal()) + String.format("%05d", returnId) + str));
	}



}
