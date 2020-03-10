all: Proxy.class Server.class FileTransmit.class

%.class: %.java
	javac $<

clean:
	rm -f *.class
