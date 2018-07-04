# SNAP NovaSAR Reader
Enables SNAP to read NovaSAR data products.

How to build
------------

Make sure you have **[git](https://git-scm.com/)**, 
**[JDK 1.8](http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html)**, and 
**[Maven 3](https://maven.apache.org/)** installed. Make sure Maven find's the JDK by setting the environment variable `JAVA_HOME` to the directory where your JDK is installed. 

Clone or fork the repository at https://github.com/bcdev/snap-novasar-reader. 
```
> git clone https://github.com/bcdev/snap-novasar-reader.git
> cd snap-novasar-reader
```

You can update your checked-out sources from the remote repository by running 
```
> git pull --rebase
```

Incremental build with Maven:
```
> mvn package
```

Clean build:
```
> mvn clean package
```  

If you encounter test failures:
```
> mvn clean package -DskipTests=true
```

The build creates a SNAP plugin module file `target/nbm/snap-novasar-reader-<version>.nbm`.

How to install and run the processor as SNAP plugin 
---------------------------------------------------

Start SNAP (Desktop UI) and find the plugin manager in the main menu at 
> **Tools / Plugins**

Then 
* select tab **Downloaded**, 
* click button **Add Files** and 
* select the plugin module file `target/nbm/s3tbx-snow-<version>.nbm`. 
* Click **Install**, 
* then **Close** and 
* restart SNAP.

Once the Reader is installed into SNAP, it can be invoked from the SNAP Desktop UI's main menu at
> **File / Import / SAR Sensors / NovaSAR**
  