/**
 * Author Xianfu Sun
 * Transparent File operation Documentation
 * Using copies of file to ensure open-close semantic
 * When initialized, the proxy first call connect to the server
 * Which will be assigned an unique client ID and use that Id to communicate with server
 * And then, for each client, their will have the client's ID too.
 * Each reader will have at least one version of the oringal file
 * The proxy will use its current version to generate read copies
 * Each writer will have their individual directories and copies of file
 * The proxy push the modified file back to the server without changing the cache
 * The Eviction follows a LRU policy
 * Only the file that has no reference count will be added back to cache queue
 * 
 */
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.rmi.Naming;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.rmi.RemoteException;
import java.util.Stack;

class Proxy {
	/**
	 * File info structure
	 * The file info stores basic information of a file
	 */
	private static class FileInfo {
		private int version; //version of the file
		private int rfcnt; //how many references to the current file
		//the file that is used in the cache
		//could be different to the real-name to avoid same-name conflicts.
		private File cache_file;
		/**
		 * Constructor for File Info
		 */
		public FileInfo(int v, int count, File f) {
			version = v;
			rfcnt = count;
			cache_file = f;
		}
	}

	/**
	 * Variables for proxy
	 */
    private static String SERVERIP; // server's ip address
    private static String SERVERPORT; //server's port num
    private static String CACHEDIR; //cache dir
    private static int CACHELIMIT; //cache limit size
    private static FileTransmit server; //reference to the server
    private static long service_id;  //service id the server returns to the Proxy
	/**
	 * file map mapping the original pathname that a client called
	 * and a structure that stores the file's info in the cache
	 */
    private static ConcurrentHashMap<String, FileInfo> fileMap;
    private static String readCache; //directory to the tmpfile created for the reading
    private static File read_cache;
    private static Integer cur_size; //current size of the cache
	/**
	 * The queue of the cached file, records its primitive pathname when
	 * the clients called
	 */
    private static ConcurrentLinkedQueue<String> cache_queue;
    private static Boolean CONNECTED; //indicate if this Proxy is connected to the server

	/**
	 * Constructor for the Proxy
	 * @param ip ip address to the server
	 * @param port port num of the server
	 * @param temp_dir cache director of the proxy
	 * @param size Maximum size of the cache
	 */
    public Proxy(String ip, String port, String temp_dir, int size) {
        SERVERIP = ip;
        SERVERPORT = port;
        CACHEDIR = temp_dir;
        CACHELIMIT = size;
        readCache = CACHEDIR + "/" + "read_tmp";
        cur_size = 0;
        fileMap = new ConcurrentHashMap<String, FileInfo>();
        cache_queue = new ConcurrentLinkedQueue<String>();
        File cacheDir = new File(CACHEDIR);
        read_cache = new File(readCache);
        try {
            cacheDir.mkdir();//mkdir for the cache folder
            read_cache.mkdir();//mkdir for the read-temp-file folder
            server = (FileTransmit)Naming.lookup("//" + ip +
                    ":" + port + "/Server");
            service_id = server.connect();   
        } catch (Exception e) {
            System.out.println(e);
        }
    }
    /**
     * class fileHandler
	 * Implement all the file operations called by the clients
     */
	private static class FileHandler implements FileHandling {
	    /**
	     * A file structure provided for handling the files
		 * It stores the raf pointer that read and write can be refered to
		 * and also other information when clients opened the file
	     */
	    private class FileStruct {
	        RandomAccessFile fd; // the "descriptor" that user read or write
	        File o_file; // the oringinal file, i.e. the cached file
	        File r_file; // the copied file for read or write purpose
	        String mode; // mode of how client want to handle this file
	        String o_path; //original path in the server
	        int version; //version num for this file
	        /**
	         * Constructor for file structure
	         */
	        public FileStruct (RandomAccessFile raf, File o, File r, String m, String orig_path, int v) {
	            fd = raf;
	            o_file = o;
	            r_file = r;
	            mode = m;
	            o_path = orig_path;
	            version = v;
	        }
	    }
	    /**
	     * Variables for FileHandler
	     */
	    private static final int MAXSIZE = 16384; //maximum chunk size for fetching file
	    private static int numOfClients = 0; //number of class totally handle
	    private int clientId;//current id od the client
		/**
		 * Each FileHandling instance manage a structure of descriptors
		 * It stores the different pointers of file_struct related to a
		 * specific client.
		 */
		private ArrayList<FileStruct> descriptors;
	    // working directory for EACH CLIENT
	    private File work;
	    /**
	     * Constructor for filehandler
	     */
	    public FileHandler() {
            clientId = numOfClients++;//add the total nums of the clients
	        descriptors = new ArrayList<FileStruct>();
	        System.out.println(String.format("proxy %d", clientId));
	        String pathname = "proxy" + String.valueOf(clientId);
	        try {
	            work = new File(Proxy.CACHEDIR + "/" + pathname);
	            work.mkdir();
	        } catch (Exception e) {
	            System.out.println("create client directory failed");
	        }      
	    }
	    /**
	     * Add a file struct to the lowest entry of the array
	     */
	    private int addDescriptor(FileStruct fs) {
	        int fd = -1;
	        for(int i = 0; i < descriptors.size(); i++) {
	            if (descriptors.get(i) == null) {
	                fd = i; //find an available entry
	                break;
	            }
	        }
            if (fd < 0) { //no available entry found when search the array
                fd = descriptors.size(); //expand the array and add to the heap
                descriptors.add(fs); 
            } else {//set the fd
                descriptors.set(fd, fs);
            }
	        return fd;
	    }

		/**
		 * fetch the file from the server and store it into a given file
		 * @param path path of the file
		 * @param cache_file the file to store
		 * @param file_size size of the target file
		 * @return 0 if succeed, else error code
		 */
	    private int fetchFile(String path, File cache_file, int file_size) {
	        try {
	            RandomAccessFile raf = new RandomAccessFile(cache_file, "rw");
	            //byte buffer to read integer
	            ByteBuffer bf = ByteBuffer.allocate(4);
	            byte[] buffer;
	            int start = 0;//start position of the file
	            if (file_size == 0) {//make at least one call.
	            	buffer = Proxy.server.transfer(path, start, MAXSIZE, Proxy.service_id);
					//the server return null explicitly when there is an error
					//happens(either no file found or IO error)
	            	if (buffer == null) {
	            		return Errors.ENOENT;
					}
				}
	            while (start < file_size) {
	                bf.position(0);//reset the position of bytebuffer
	                buffer = Proxy.server.transfer(path, start, MAXSIZE, Proxy.service_id);
	                if (buffer == null) {//an error case
	                    return Errors.ENOENT;
	                }
	                int read_size;
	                bf.put(buffer, 0, 4);
	                bf.position(0);
	                read_size = bf.getInt();//read the readsize in the first four bytes
	                if (read_size == -1) {//EOF
	                    break;
	                }
	                start += read_size;
	                raf.write(buffer, 4, read_size);//start to write file from the fourth byte
	            }
	            raf.close();
	        } catch (Exception e) {
	            System.out.println(e);
	            return Errors.ENOENT;
	        }
	        return 0;
	    }

		/**
		 * Push the file back to the server
		 * @param f the file to push
		 * @param o_path the path of the original file that the server stores
		 * @return 0 on success, else Error code
		 */
	    private int pushBack(File f, String o_path) {
	        try {
	            long start = 0;
	            RandomAccessFile raf = new RandomAccessFile(f, "r");
	            byte[] buffer = new byte[MAXSIZE];
	            int size = raf.read(buffer);
	            while (size != -1) { //keep pushing the file until eof
	                int rv = Proxy.server.push(o_path, buffer, size, start, Proxy.service_id);
	                if (rv < 0) { //error case
	                    System.out.println("Errors when push back to Server");
	                    return Errors.EINVAL;
	                }
	                start += size; //update the start point for next push
	                size = raf.read(buffer);
	            }
	            //send server an eof message
	            Proxy.server.push(o_path, null, size, start, Proxy.service_id);
	            raf.close();
	        } catch (Exception e) {
	            System.out.println(e);
	            return Errors.EINVAL;
	        }
	        return 0;
	    }

		/**
		 * Evict the file one cache is full
		 * Following LRU policy
		 * @param required_size size required in the cache
		 * @return true on success, fasle if failed
		 */
	    private synchronized Boolean evict(int required_size) {
	    	//space left in the cache
	        int left_size = Proxy.CACHELIMIT - Proxy.cur_size;
	        while (left_size < required_size) { //keep evicting files until requirements meet.
	            if (Proxy.cache_queue.isEmpty()) {
	            	//the cache queue is empty
					//indicate all files are in use, no file can be evicted
	            	System.out.println("No file can be evicted!");
	                return false;
	            }
	            //poll one file out of the queue
	            String path = Proxy.cache_queue.poll();
	            //remove the file from the cache maps
				//it could has already been removed by other function
	            if (Proxy.fileMap.containsKey(path)) {
	            	FileInfo f_info = Proxy.fileMap.get(path);
	            	//get the real file stored in the cache
	            	File cache_file = f_info.cache_file;
	            	//remove the entry
	            	Proxy.fileMap.remove(path);
	            	//update the size
					left_size += cache_file.length();
					Proxy.cur_size -= (int)cache_file.length();
					//delete that file to free space
					cache_file.delete();
				}
	        }
	        return true;
	    }

		/**
		 * simplified path, remove all the ./ and ../ if possible
		 * return null if it is an illegal path(try to get to the root dir)
		 * @param path the original path to simplify
		 * @return return the simplified path if succeed
		 * return null if illegal cases happened
		 */
	    private String simplifyPath(String path) {
	            String rv = "";
	            //create a stack to store the directory's name
	            Stack<String> stack = new Stack<String>();
	            String[] p = path.split("/");
	            for(String s: p) {
	                if (s.equals("..")) {
	                    if (stack.isEmpty()) {
							// tried to get to the parent of the root
							//illefal cases
	                        return null;
	                    }
	                    //pop out one directory
	                    stack.pop();
	                } else if (!s.equals(".")) {
	                	//ignore all the ./
	                    stack.push(s);
	                }
	            }
	            //reconstruct the simplified path
	            while (!stack.isEmpty()) {
	                rv = "/" + stack.pop() + rv;
	            }
	            //ignore the first "/".
	            return rv.substring(1);
	    }
	    /**
	     * recursively check the size of the proxy's cache
	     */
	    private int checkSize(File root) {
	    	int sum = 0;
	    	File[] f = root.listFiles();
	    	for (int i = 0; i < f.length; i ++) {
	    		if (f[i].isDirectory()) {
	    			sum += checkSize(f[i]);
				} else {
	    			sum += f[i].length();
				}
			}
	    	return sum;
	    }
	    /**
	     * Check the availability of a file in the cache
	     * It will either copy the file from the server on cache hit
	     * or check the version code on cache hit
	     * In either case it returns the correct version code
	     * If the cache does not have enough space, it will evict one file from cache
	     * If none of the cache is evictable, then return error
		 * @params path the original path the clients made the request
	     */
	    public File checkCache(String path) {
    	       int version = 0;
    	       int file_size = 0;
    	       File f = new File(path);
    	       f = new File(Proxy.CACHEDIR + "/" + f.getName());
               if (!Proxy.fileMap.containsKey(path)) { //cache miss
                    try {
						int[] fileInfo = Proxy.server.getFileInfo(path);
						version = fileInfo[0];
						file_size = fileInfo[1];
                        //check the remaining size of cache
                        synchronized (Proxy.cur_size) {
                        	Proxy.cur_size = checkSize(new File(Proxy.CACHEDIR));
                            if (Proxy.cur_size + file_size > Proxy.CACHELIMIT) {
                                Boolean rv = evict(file_size);
                                if (!rv) { //errors when evict one cache file
                                	System.out.println("too many files opened!");
                                    return null;
                                }
                            }
                            Proxy.cur_size += file_size;
                        }
                        //check if there are same-naming file in the cache
						if(f.exists()) {
							f = f.createTempFile(f.getName(), "RENAMED", new File(Proxy.CACHEDIR));
						}
                        int rv = fetchFile(path, f, file_size);
                        if (rv >= 0) {
                            //only put this into the map when there is one exist on the server
							if (Proxy.fileMap.containsKey(path)) {
								f.delete();//delete redundant copied file
								f = Proxy.fileMap.get(path).cache_file;
							} else {
								Proxy.fileMap.put(path, new FileInfo(version, 1, f));
							}
                            return f;
                        } else {
                            //delete that not existing file in the cache
                            //no file found in the server does not indicate error
                            f.delete();
                            return f;
                        }
                    } catch (Exception e) {
                        return null;
                    }
                } else {//cache hit, check on use
				   	FileInfo f_info = Proxy.fileMap.get(path);
				   	int old_version = f_info.version;
                    try {
                    	//get the information of the file
						int[] fileInfo = Proxy.server.getFileInfo(path);
						version = fileInfo[0];
						file_size = fileInfo[1];
                        if (version != old_version) { //the version has been updated
                        	if (version == -1) { //the server does not have version for this file
                        		f_info.cache_file.delete(); //delete that file on the cache
								Proxy.fileMap.remove(path); //remove the entry on the fileMap
								return f_info.cache_file;  //return the original cached file
							}
                        	//ask the server to retransmit this file
                            f = f_info.cache_file;
                            f.delete();
                            f.createNewFile();
                            int rv = fetchFile(path, f, file_size);
                            f_info.rfcnt++;
                            f_info.version = version;//replace the version
							//remove the path from the cache-eviction queue
							Proxy.cache_queue.remove(path);
                            return f;
                        } else { //no version update on the server
                        	f_info.rfcnt++; //add the reference count
							// remove this file from the queuelist of the file
                        	Proxy.cache_queue.remove(path);
                            return f_info.cache_file;
                        }
                    } catch (Exception e) {
                        System.out.println(e);
                        return null;
                    }
                }
    	    }

		/**
		 * open the file operation
		 * It first checks the cache for cases(cache miss or hit)
		 * And get then create filestruct according to different opening mode
		 * And then it find the lowest entry of the descriptor structure to
		 * store the file structure and return the number of the entry
		 * @param pathname path name passed by the clients
		 * @param o operation options
		 * @return 0 on success, else the corresponding error code
		 */
		public synchronized int open( String pathname, OpenOption o ){
			//get a simplified path
		    String path = simplifyPath(pathname);
		    if (path == null) { //illegal pathname
		        return Errors.EINVAL;
		    }
		    //check the cache and get the real file in cache it need to open
		    File f = checkCache(path);
		    int version = 0;
		    //get the version number of the file
		    if (Proxy.fileMap.containsKey(path)) {
		    	version = Proxy.fileMap.get(path).version;
			}
		    switch (o) {
		        case CREATE: //case create
                    try {
                    	// duplicate the file for writing.
                        File r_file = new File(work.getPath() + "/" + f.getName());
                        int fd = 0;
						/**
						 * do not create extra file if
						 * 1. original file does not exists, it may be creating a new file
						 * 2. the replicated file already exists, it may be client open
						 * the same file again
						 * 3. the file is a directory
						 * other mode of openeing do this check as well
						 */
                        if (f.exists() && !r_file.exists() && !f.isDirectory()) {
                        	synchronized (Proxy.cur_size) {
                        		//check the cache size
								Proxy.cur_size = checkSize(new File(Proxy.CACHEDIR));
								if (Proxy.cur_size + f.length() > Proxy.CACHELIMIT) {
									Boolean rv = evict((int)f.length());
									if (!rv) { //no enough space for creating extra writing replica
										return Errors.EBUSY;//no enough space in the cache
									}
									Proxy.cur_size += (int)f.length();
								}
								Files.copy(f.toPath(), r_file.toPath());
							}
						}
                        if (f.isDirectory()) { //case file is a directory
                            FileStruct fs = new FileStruct(null, f, null, "write", path, version);
                            return addDescriptor(fs);
                        }
                        //create a file struct for this file
                        RandomAccessFile raf = new RandomAccessFile(r_file, "rw");
                        FileStruct fs = new FileStruct(raf, f, r_file, "write", path, version);
                        // add to the structure
                        fd = addDescriptor(fs);
                        return fd;
                    } catch (IOException e) { // handling exception
                        System.out.println("exception in open CREATE");
						//in exception add back this file to the queue
                        Proxy.cache_queue.add(path);
                        return Errors.EINVAL;
                    }
		            
		        case CREATE_NEW: //case create new
		            try {
		              int fd = 0;
	                  if (f.exists()) {//file exists
	                        return Errors.EEXIST;
	                  } else {
	                        f.createNewFile();
	                        //immediately push this file back first.
						  	// so other clients will know this file exists
						  	try {
								pushBack(f, path);
							} catch (Exception e) {
						  		System.out.println("push empt file back to server failed");
							}
	                  }
	                  //create entry for this file
	                  RandomAccessFile raf = new RandomAccessFile(f, "rw");
	                  FileStruct fs = new FileStruct(raf, f, f, "write", path, version);
	                  //add to the structure
	                  fd = addDescriptor(fs);
	                  return fd;
		            } catch (Exception e) {
		                System.out.println("exception in open CREATENEW");
		                return Errors.EINVAL;
		            }

		        case READ:  //case read only
		            try {
		                int fd = 0;
		                if (!f.exists()) {
		                    System.out.println("file does not exist");
		                    return Errors.ENOENT;
		                }
                        if (f.isDirectory()) {
                            FileStruct fs = new FileStruct(null, f, null, "read", path, version);
                            return addDescriptor(fs);
                        }
                        //Construct a possible copy of a read file
                        File r_file = new File(Proxy.readCache + "/" + f.getName()
                                                + "-" + String.valueOf(version));
                        // if a read file with the same version is already created in the cache
						// do not create again, to save cache space
		                if (!r_file.exists()) {
		                	//check cache size for the replica
							synchronized (Proxy.cur_size) {
								Proxy.cur_size = checkSize(new File(Proxy.CACHEDIR));
								if (Proxy.cur_size + f.length() > Proxy.CACHELIMIT) {
									Boolean rv = evict((int)f.length());
									if (!rv) {
										return Errors.EBUSY;//no enough space in the cache
									}
								}
								Proxy.cur_size += (int)f.length();
								Files.copy(f.toPath(), r_file.toPath());
							}
		                }
		                //create an entry of this file.
		                RandomAccessFile raf = new RandomAccessFile(r_file, "r");
		                FileStruct fs = new FileStruct(raf, f, r_file, "read", path, version);
                        fd = addDescriptor(fs);
		                return fd;	                
		            }catch (Exception e) {
		                System.out.println("exception in open, READ");
		                Proxy.cache_queue.add(path);
		                return Errors.EINVAL;  
		            }
			 	case WRITE:  //case read only
                    try {
                        int fd = 0;
                        if (!f.exists()) {
                            System.out.println("file does not exist");
                            return Errors.ENOENT;
                        }
                        if (f.isDirectory()) {
                            FileStruct fs = new FileStruct(null, f, null, "write", path, version);
                            return addDescriptor(fs);
                        }
                        File r_file = new File(work.getPath() + "/" + f.getName() + ".temp");
                        //avoid duplicated  creating
                        if (!r_file.exists()) {
							synchronized (Proxy.cur_size) {
								Proxy.cur_size = checkSize(new File(Proxy.CACHEDIR));
								if (Proxy.cur_size + f.length() > Proxy.CACHELIMIT) {
									Boolean rv = evict((int)f.length());
									if (!rv) {
										return Errors.EBUSY;//no enough space in the cache
									}
								}
								Proxy.cur_size += (int)f.length();
								Files.copy(f.toPath(), r_file.toPath());
							}
                        }
                        //create an entry for that file
                        RandomAccessFile raf = new RandomAccessFile(r_file, "rw");
                        FileStruct fs = new FileStruct(raf, f, r_file, "write", path, version);
                        fd = addDescriptor(fs);
                        return fd;
                    } catch (Exception e) {
                        System.out.println(e);
                        Proxy.cache_queue.add(path);
                        return Errors.EINVAL;  
                    }
		    }//switch
		    return Errors.EINVAL;
		}
/**
 * close operation
 * @param fd descriptor of the file
 * @return 0 on success, otherwise error code.
 */
		public int close( int fd ) {
		    if (fd < 0) { //error case
		        return Errors.EBADF;
		    }
            if (fd >= descriptors.size()) {
            	// the descriptor has not been allocated
                return Errors.EBADF;
            }
            //get the filestruct by using the descriptor
			FileStruct fs = descriptors.get(fd);
            if (fs == null) {
                return Errors.EBADF;
            }
			//close the file
            try {
              if (fs.o_file.isDirectory()) {
              	//path is a directory
                  return Errors.EISDIR;
              }
              if (fs.mode.equals("write")) {
                //we should use this file to replace the original file
                  pushBack(fs.r_file, fs.o_path);
              }
              fs.fd.close();//close the file
              descriptors.set(fd, null); //free this entry
              String cache_name = fs.o_path; // the name of this file used in the cache
              if (Proxy.fileMap.containsKey(cache_name)) {
              	//update the related information
              	FileInfo f_info = Proxy.fileMap.get(cache_name);
              	f_info.rfcnt--;
              	if (f_info.rfcnt == 0) {//safely add this into the cache
              		Proxy.cache_queue.add(fs.o_path);
              		fs.r_file.delete();
				}
			  }
            }catch (IOException e) {
               System.out.println("error in close");
               System.out.println(e);
               return Errors.EINVAL;
            }
            return 0;

		}
/**
 * write operation
 * @param buf buf provide by the client to write readed bytes.
 * @param fd descriptor (the entry of the file structure)
 * @return 0 on sucess, otherwise correspoding error code.
 */
		public long write( int fd, byte[] buf ) {
		    FileStruct fs;
		    if (fd < 0) { //error case
		        return Errors.EBADF;
		    }
	        if (fd >= descriptors.size()) {
	        	// not allocated entry
	            System.out.println("file does not exist!");
	            return Errors.EBADF;
	        }
	        fs = descriptors.get(fd);
		    if (fs == null) {
		    	//a illegal entry
		        System.out.println("file does not exist!");
		        return Errors.EBADF;
		    }
		    try {
		        if (fs.o_file.isDirectory()) { //directory
		            return Errors.EISDIR;
		        }
		        fs.fd.write(buf);
		        return buf.length;
		    } catch(IOException e) {
		        System.out.println("IOException in write");
		        return Errors.EBADF;
		    }
		}
/**
 * read operation
 * @param fd descriptor (the entry for the file structure)
 * @param buf buf provided by the client
 */
		public long read( int fd, byte[] buf ) {
		    if (fd < 0) { //error case
		        return Errors.EBADF;
		    }
		    FileStruct fs;
	        if (fd >= descriptors.size()) {
	        	//not allocated space
	            System.out.println("file does not exist!");
	            return Errors.EBADF;
	        } else {
	            fs = descriptors.get(fd);
	            if (fs == null) {
	            	//illegal case
	                System.out.println("file does not exist!");
                    return Errors.EBADF;
	            }
	        }
		    try {
		        if (fs.o_file.isDirectory()) { //is directory
		            return Errors.EISDIR;
		        }
	            int rv = fs.fd.read(buf);
	            if (rv == -1) { //different to C on EOF
	                return 0; //return the EOF sign
	            }
	            return rv;		        
		    } catch (IOException e) {
		        System.out.println("read failed");
		        return Errors.EINVAL;
		    }
		}
/**
 * lseek operation
 * @param fd entry of the filestruct
 * @pos offset denote by client
 * @o lseek option
 * @return current position on success, else error code
 */
		public long lseek( int fd, long pos, LseekOption o ) {
		    FileStruct fs;
            if (fd < 0 || fd > descriptors.size()) {
            	//illegal entry
                return Errors.EBADF;
            }
            fs = descriptors.get(fd); 
            RandomAccessFile raf = fs.fd;
		    if (fs == null) { //illegal entry
		        return Errors.EBADF;
		    }
		    if (fs.o_file.isDirectory()) { //is directory
		        return Errors.EISDIR;
		    }
		    switch (o) {
		        case FROM_CURRENT: //case from current postion
		            try {
		                long length = raf.length();
		                long cur_pos = raf.getFilePointer();
		                raf.seek(cur_pos + pos);
		                return cur_pos + pos;
		            } catch (IOException e) {
		                System.out.println("lseek failed IO, FROM_CURRENT");
		                return Errors.EINVAL;
		            }
		        case FROM_START: //case from the begining of the file
		            try {
		                long length = raf.length();
		                long cur_pos = raf.getFilePointer();
		                raf.seek(pos);
		                return pos;
		            } catch (IOException e) {
		                System.out.println("lseek faied IO, FROM_START");
		                return Errors.EINVAL;
		            }
		        case FROM_END: //case from the end of the file
		            try {
		                long length = raf.length();
		                long cur_pos = raf.getFilePointer();
		                long position = length - pos - 1;
		                raf.seek(position);
		                return position;
		            } catch (IOException e) {
		                System.out.println("lseek faied IO, FROM_END");
		                return Errors.EINVAL;
		            }
		    }
            return Errors.EINVAL;
		}

/**
 * unlink one file
 * @param pathname
 * @return 0 on success, else error code
 */
		public int unlink( String pathname ) {
			//simplify the pathname
		    String path = simplifyPath(pathname);
		    if (path == null) { //try to access a parent directory.
		        return Errors.EINVAL;
		    }
			//delete that file from the server
		    try {
		        Boolean rv = Proxy.server.delete(path);
		        if (!rv) {//false indicate an nonexistent file
		        	return Errors.ENOENT;
				}
		        if (Proxy.fileMap.containsKey(path)) {//delete that from the cache as well
		        	FileInfo f_info = Proxy.fileMap.get(path);
		        	f_info.cache_file.delete(); //delete file
		        	Proxy.fileMap.remove(path); //remove from the file map
		        	Proxy.cache_queue.remove(path); //remove from the eviction queue
				}
		    } catch (Exception e) {
		        System.out.println(e);
		        return Errors.EINVAL;
		    }
			return 0;
		}

		/**
		 * client done. do cleaning works
		 */
		public synchronized void clientdone() {
		    //clear the directory and all the temp files inside
		    File[] files = work.listFiles();
		    for (int i = 0; i < files.length; i++) {
		        files[i].delete();
		    }
		    Boolean rv = work.delete();
			return;
		}
	}
	
	private static class FileHandlingFactory implements FileHandlingMaking {
		public FileHandling newclient() {
			return new FileHandler();
		}
	}
	//main
	public static void main(String[] args) throws IOException {
		System.out.println("Hello World");
		//read the proxy arguments
		String ip = args[0];
		String port = args[1];
		String cache_dir = args[2];
		int limit = Integer.parseInt(args[3]);
		//create proxy instance
		new Proxy(ip, port, cache_dir, limit);
		(new RPCreceiver(new FileHandlingFactory())).run();
		//call disconnect to the server when exit.
		try {
			Proxy.server.disconnect(Proxy.service_id);
		} catch (RemoteException e) {
			System.out.println(e);
		}
		//clean up the reading directory
		File[] readFiles = Proxy.read_cache.listFiles();
		for (int i = 0; i < readFiles.length; i++) {
			readFiles[i].delete();
		}
	}
}

