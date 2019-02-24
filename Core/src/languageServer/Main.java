package languageServer;


public class Main {

	public static void main(String[] args) {
		LanguageServer s = LanguageServer.getInstance();
		s.setPort(8000);
		s.run();
	}
	
}
