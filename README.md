### The MultiLanguage framework allows for multiple languages to work together and behave as one program. 
The framework consists of a Java core, and a module for every language being used. Current supported modules are Python and Java, but it is simple to add more. 

## Usage
To use another language's class definitions, first the language must register the class. Let us take an example class:

    public class Foo {

		@Global
		public Foo() {
		
		}
	
		@Global
		public String bar(String param) {
			System.out.println(param);
			return "this is a returned string";
		}
	
		public void foo() {
		
		}
	}

To Register this class, simply do:

    LanguageClient client = new LanguageClient("localhost", 8000);
	client.registerClass(HealthAdvice.class);
The localhost and port are where the Java core are running, and defaults to port 8000. Only methods and constructors marked with @Global can be used.

Similarly in python:

    class Foo:
	    @Global
		def __init__(a, b):
			pass
	
		@Global
		def foo(a, b):
			pass

and to register:

    client = LanguageClient('localhost', 8000)
    client.register_class(Foo)

Once a class has been registered, other languages can instantiate the object and use its methods.
Java:

    try {
		client.createObject("Foo", "variableName", "construtor arg1", "constructor arg2...");
		client.callMethod("variableName", "bar", true, "method arg1", "method arg2...");
	} catch (ClassNotFoundException | InvalidObjectException e) {
		e.printStackTrace();
	}

Python:
	

    client.create_object("Foo", "name", "test", 1, 2)
    returnVal = client.call_method("dogVar", "bar", True, "test string 1")

