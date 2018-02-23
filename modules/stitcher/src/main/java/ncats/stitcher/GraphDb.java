package ncats.stitcher;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

import java.util.function.Function;
import java.util.logging.Logger;
import java.util.logging.Level;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.event.*;
import org.neo4j.graphdb.schema.IndexDefinition;
import org.neo4j.graphdb.schema.IndexCreator;


/**
 * wrapper around GraphDatabaseService instance
 */
public class GraphDb extends TransactionEventHandler.Adapter
    implements KernelEventHandler {
    
    static final String DEFAULT_CACHE = "cache"; // cache

    static final Logger logger = Logger.getLogger(GraphDb.class.getName());

    static final Map<File, GraphDb> INSTANCES =
        new ConcurrentHashMap<File, GraphDb>();

    protected final File dir;
    protected final GraphDatabaseService gdb;
    protected CacheFactory cache;
    protected boolean localCache;
    protected final AtomicLong lastUpdated = new AtomicLong ();
    
    protected final File indexDir;
    
    protected GraphDb (File dir) throws IOException {
        this (dir, null);
    }

    void createIndex (Label label, String name) {
        IndexCreator index = gdb.schema().indexFor(label).on(name);
        try {
            IndexDefinition def = index.create();
            /*
              gdb.schema().awaitIndexOnline
              (def, 100, TimeUnit.SECONDS);*/
        }
        catch (Exception ex) {
            logger.info("Index \""+name
                        +"\" already exists for node "+label+"!");
        }
    }
    
    protected GraphDb (File dir, CacheFactory cache) throws IOException {
        gdb = new GraphDatabaseFactory().newEmbeddedDatabaseBuilder(dir)
            .setConfig(GraphDatabaseSettings.dump_configuration, "true")
            .newGraphDatabase();

        /*
        try (Transaction tx = gdb.beginTx()) {
            createIndex (CNode.CLASS_LABEL, Props.PARENT);
            createIndex (AuxNodeType.SNAPSHOT, Props.PARENT);
            tx.success();
        }
        */
        
        gdb.registerTransactionEventHandler(this);
        gdb.registerKernelEventHandler(this);

        indexDir = new File (dir, "index");
        if (!indexDir.exists()) {
            indexDir.mkdirs();
        }

        // this must be initialized after graph initialization
        if (cache == null) {
            this.cache = CacheFactory.getInstance
                   (new File (indexDir, DEFAULT_CACHE));
            localCache = true;
        }
        else {
            this.cache = cache;
            localCache = false;
        }
        this.dir = dir;
    }

    @Override
    public void afterCommit (TransactionData data, Object state) {
        lastUpdated.set(System.currentTimeMillis());
    }

    public long getLastUpdated () { return lastUpdated.get(); }
    public GraphDatabaseService graphDb () { return gdb; }
    public CacheFactory getCache () { return cache; }
    public void setCache (CacheFactory cache) {
        if (cache == null)
            throw new IllegalArgumentException ("Cache can't be null");
        this.cache.shutdown();
        this.cache = cache;
    }
    
    public File getPath () { return dir; }
    public void shutdown () {
        gdb.shutdown();
        if (localCache)
            cache.shutdown();
    }

    public CNode getNode (long id) {
        CNode cnode = null;
        try (Transaction tx = gdb.beginTx()) {
            Node node = gdb.getNodeById(id);
            if (node != null) {
                for (EntityType t : EnumSet.allOf(EntityType.class)) {
                    if (node.hasLabel(t)) {
                        cnode = Entity._getEntity(node);
                        break;
                    }
                }
                if (cnode == null)
                    cnode = new CNode (node);
            }
            tx.success();
        }
        return cnode;
    }

    /*
     * KernelEventHandler
     */
    public void beforeShutdown () {
        logger.info("Instance "+dir+" shutting down...");
        INSTANCES.remove(dir);
    }

    public Object getResource () {
        return null;
    }

    public void kernelPanic (ErrorState error) {
        logger.log(Level.SEVERE, "Graph kernel panic: "+error);
    }

    public KernelEventHandler.ExecutionOrder orderComparedTo
        (KernelEventHandler other) {
        return KernelEventHandler.ExecutionOrder.DOESNT_MATTER;
    }

    public static GraphDb createTempDb () throws IOException {
        return createTempDb ("_ix");
    }

    public static GraphDb createTempDb (String prefix) throws IOException {
        File file = Files.createTempDirectory(prefix).toFile();
        GraphDb graphDb = new GraphDb (file);
        INSTANCES.put(file, graphDb);
        return graphDb;
    }

    public static GraphDb getInstance (String dir) throws IOException {
        return getInstance (new File (dir));
    }
    
    public static GraphDb getInstance (String dir, CacheFactory cache)
        throws IOException {
        return getInstance (new File (dir), cache);
    }

    public static synchronized GraphDb getInstance (File dir)
        throws IOException {
        return getInstance (dir, null);
    }
    interface ThrowingFunction<T, R, E extends Throwable>{
        R apply(T t) throws E;
    }

    public static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
        throw (E) e;
    }
    private static <T,R, E extends Throwable> Function<T,R> uncheck(ThrowingFunction<T,R,E> f){
        return t -> {
            try {
                return f.apply(t);
            } catch (Throwable e) {
                sneakyThrow(e);
            }
            return null; // can't happen but makes compiler happy
        };
    }
    public static synchronized GraphDb getInstance(File dir, CacheFactory cache) throws IOException {

        return INSTANCES.computeIfAbsent(dir, uncheck(f-> new GraphDb(f, cache)));
//        GraphDb gdb = INSTANCES.get(dir);
//        if (gdb == null) {
//            INSTANCES.put(dir, gdb = new GraphDb (dir, cache));
//        }
//        return gdb;
    }

    public static GraphDb getInstance (GraphDatabaseService gdb) {
        for (GraphDb db : INSTANCES.values())
            if (db.graphDb() == gdb)
                return db;
        return null;
    }

    public Indexer getIndexer (Integer version) throws IOException {
        return Indexer.getInstance(new File (indexDir, "v"+version));
    }

    public static void addShutdownHook () {
        Runtime.getRuntime().addShutdownHook(new Thread() {
                // do shutdown work here
                public void run () {
                    for (GraphDb gdb : INSTANCES.values()) {
                        gdb.graphDb().shutdown();
                        logger.info
                            ("##### Shutting Down Graph Database: "
                             +gdb.dir+" #####");
                    }
                }
            });
    }
}
