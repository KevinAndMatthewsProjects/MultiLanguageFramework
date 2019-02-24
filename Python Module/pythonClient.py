import queue, threading, json, socket, inspect, traceback, time
from enum  import Enum

class LanguageClient:

    def __init__(self, host, port):

        self.host = host
        self.port = port

        self.global_local_objects = {}
        self.outgoing = queue.Queue(0)
        self.returnQueue = queue.Queue(1)
        
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.connect( (self.host, self.port) )
        print("Connected")

        thread = threading.Thread(target=self.runInput, args=(s,))
        thread.start()
        thread2 = threading.Thread(target=self.runOutput, args=(s,))
        thread2.start()

    def runInput(self, socket):
        def socket_readln(initdata = b''):
            try:
                line = initdata.decode("utf-8")
                while True:
                    data = socket.recv(1).decode("utf-8")
                    line += data
                    if '\r\n' in line:
                        break
                return line.splitlines()[0]
            except Exception as e:
                print(str(e), flush=True)

        while(True):

            try:
                time.sleep(1)
                print("before read", flush=True)
                hasData = socket.recv(1)
                if hasData:
                    line = socket_readln(hasData)
                    print("read line: " + str(line))
                    action = line[0:5]
                    returnID = int(line[5:10])
                    data = line[10:]

                    getattr(self, LanguageOperations(int(action)).name + "_remote")(data, returnID)
            except Exception as e:
                print(str(e), flush=True)

    def runOutput(self, socket):
        while(True):
            try:
                message = self.outgoing.get()
                socket.sendall((message + '\n').encode('utf-8') )
                print("sent: " + message, flush=True)
            except Exception as e:
                print(str(e), flush=True)

            
    
    def write_command(self, operation, data):
        try:
            self.outgoing.put(str(operation.value).zfill(5) + data, block=False)
        except Exception as e:
            print(str(e), flush=True)

    def register_class(self, c):
        class_obj = {"name" : c.__name__}
        methods = [getattr(c, field) for field in dir(c) if hasattr(getattr(c, field), "_is_global")]
        paramaterArr = ["any" for x in inspect.getfullargspec(method).args]
        method_data = [{"name" : method.__name__, "parameters" :  paramaterArr[1:], "return" : "any"} for method in methods if method.__name__ != "__init__"]
        
        constructor_data = [["any" for x in inspect.getfullargspec(method).args][1:] for method in methods if method.__name__ == "__init__"]
        class_obj['methods'] = method_data
        class_obj['constructors'] = constructor_data

        jsonStr = json.dumps(class_obj, separators=(',',':'))
        self.write_command(LanguageOperations.register_class, jsonStr)

    def create_object(self, class_name, var_name, *args):

        paramsArr = []
        #paramsArr.append({str(None) : str(self.standardize(type(None)))})
        for arg in args:
            paramsArr.append({str(arg) : str(self.standardize(type(arg)))})
        objData = {"name" : class_name, "arguments" : paramsArr, "varName" : var_name}
        
        jsonStr = json.dumps(objData, separators=(',',':'))
        print(jsonStr)

        self.write_command(LanguageOperations.create_object, jsonStr)

    def create_object_remote(self, json_data, returnID):
        objData = json.loads(json_data)
        class_name = objData['name']
        varName = objData['varName']
        args = objData['arguments']

        typedArgs = [self.cast(list(a.keys())[0], list(a.values())[0]) for a in args]
        my_class = self.get_class(class_name)
        instance = my_class(*typedArgs[1:])
        self.global_local_objects[varName] = instance
        print(str(instance))

    def call_method(self, var_name, method_name, has_return_val, *params):
        print("calling method ", flush = True)
        params_arr = []
        try:
            for param in params:
                params_arr.append( {str(param) : self.standardize(type(param))})
            objData = {"name" : method_name, "arguments" : params_arr, "varName" : var_name}

            jsonStr = json.dumps(objData, separators=(',',':'))
            print(jsonStr)
        except Exception as e:
            print(str(e), flush=True)
        self.write_command(LanguageOperations.call_method, jsonStr)

        if(has_return_val):
            print("Waiting on return q", flush = True)
            returnValObj = self.returnQueue.get(True)
            returnType = returnValObj['returnType']
            returnVal = returnValObj['returnVal']
            return self.cast(returnVal, returnType)
        else:
            return None

    def call_method_remote(self, json_data, return_id):
        print("Calling method with data " + str(json_data), flush = True)
        objData = json.loads(json_data)
        method_name = objData['name']
        varName = objData['varName']
        args = objData['arguments']

        typedArgs = [self.cast(list(a.keys())[0], list(a.values())[0]) for a in args]

        retVal = getattr(self.global_local_objects[varName], method_name)(*typedArgs[1:])
        retObj = {"returnID" : return_id, "returnVal" : str(retVal), "returnType" : self.standardize(type(retVal))}
        jsonStr = json.dumps(retObj)
        self.write_command(LanguageOperations.return_val, jsonStr)
    
    def return_val_remote(self, json_data, returnID):
        print("returning with data " + json_data)
        try:
            retData = json.loads(json_data)
            print("before put q", flush = True)
            self.returnQueue.put(retData)
            print("after put q", flush = True)
        except Exception as e:
            print(str(e))


    def get_class(self, kls):
        try:
            m = globals()[kls]
            return m
        except Exception as e:
            print(str(e), flush=True)


    def standardize(self, string):
        if string == type(""):
            return "string"
        elif string == type(1):
            return "int"
        elif string == type(True):
            return "bool"
        elif string == type(1.0):
            return "float"
        elif string == type(None):
            return "null"
        else:
            return string

    def cast(self, value, val_type):
        if val_type == 'int':
            return int(value)
        elif val_type == 'string':
            return str(value)
        elif val_type == 'null':
            return None


def is_global(func):
    return hasattr(func, '_is_global')

def Global(func):
    func._is_global=True
    return func

class Foo:

    @Global
    def __init__(self, a, b, c):
        print("a: " + str(a) + " b: " + str(b))
        pass

    @Global
    def bar(self, a, b):
        print("in BAR a: " + str(a) + " b: " + str(b))
        return "this is a string"


class LanguageOperations(Enum):
    nothing = 0
    error = 1
    init = 2
    return_val = 3
    register_class = 4
    create_object = 5
    call_method = 6

'''
try:
    client = LanguageClient('localhost', 8000)
    #client.register_class(Foo)
    #print("done register")
    #client.create_object("Foo", "name", "test", 1, 2)
    #print("done create object")
    returnVal = client.call_method("dogVar", "bar", True, "test string 1")
    print("returned with " + str(returnVal))
    returnVal = client.call_method("dogVar", "bar", True, "test string 2")
    print("returned with " + str(returnVal))
    #print("remote function returned: " + str(client.call_method("name", "bar", True, "a", 2)))
except Exception as e:
    print(str(e), flush=True)
'''
