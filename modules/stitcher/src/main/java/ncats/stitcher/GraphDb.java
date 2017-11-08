package ncats.stitcher;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import java.util.logging.Logger;
import java.util.logging.Level;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.factory.GraphDatabaseBuilder;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.config.Setting;
import org.neo4j.graphdb.event.*;
import org.neo4j.graphdb.schema.IndexDefinition;
import org.neo4j.graphdb.schema.IndexCreator;

import org.apache.lucene.store.*;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.search.*;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.facet.*;
import org.apache.lucene.facet.taxonomy.directory.*;

/**
 * wrapper around GraphDatabaseService instance
 */
public class GraphDb extends TransactionEventHandler.Adapter
    implements KernelEventHandler {
    
    static final String DEFAULT_CACHE = "cache"; // cache
    static final String DEFAULT_FACET = "facet"; // facet index
    static final String DEFAULT_TEXT = "text"; // lucene index
    static final String DEFAULT_SUGGEST = "suggest"; // suggest index

    static final Logger logger = Logger.getLogger(GraphDb.class.getName());

    static final Map<File, GraphDb> INSTANCES =
        new ConcurrentHashMap<File, GraphDb>();


    protected final File dir;
    protected final GraphDatabaseService gdb;
    protected CacheFactory cache;
    protected boolean localCache;
    protected final AtomicLong lastUpdated = new AtomicLong ();
    
    protected final File indexPath;
    protected Directory textDir;
    protected Directory facetsDir;
    protected IndexWriter indexWriter;
    protected DirectoryTaxonomyWriter facetsWriter;
    protected FacetsConfig facetsConfig; 
    protected IndexSearcher indexSearcher;
    
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

        indexPath = new File (dir, "index/lucene");
        if (!indexPath.exists()) {
            indexPath.mkdirs();
        }
        initLucene (indexPath);

        // this must be initialized after graph initialization
        if (cache == null) {
            this.cache = CacheFactory.getInstance
                   (new File (indexPath, DEFAULT_CACHE));
            localCache = true;
        }
        else {
            this.cache = cache;
            localCache = false;
        }
        this.dir = dir;
    }

    protected void initLucene (File base) throws IOException {
        File dir = new File (base, DEFAULT_TEXT);
        dir.mkdirs();
        textDir = new NIOFSDirectory (dir.toPath());
        IndexWriterConfig config =
            new IndexWriterConfig (new StandardAnalyzer ());
        indexWriter = new IndexWriter (textDir, config);
        
        dir = new File (base, DEFAULT_FACET);
        dir.mkdirs();
        facetsDir = new NIOFSDirectory (dir.toPath());
        facetsWriter = new DirectoryTaxonomyWriter (facetsDir);
        facetsConfig = new FacetsConfig ();
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
        try {
            IOUtils.close(indexWriter);
            IOUtils.close(textDir);
            IOUtils.close(facetsWriter);
            IOUtils.close(facetsDir);
        }
        catch (IOException ex) {
            logger.log(Level.SEVERE, "Can't close Lucene handles", ex);
        }
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
        return createTempDb (null, null);
    }

    public static GraphDb createTempDb (String name) throws IOException {
        return createTempDb (name, null);
    }

    public static GraphDb createTempDb (File temp) throws IOException {
        return createTempDb (null, temp);
    }
    
    public static GraphDb createTempDb (String name, File temp)
        throws IOException {
        return createTempDb ("_ix"+(name != null ? name:""), ".db", temp);
    }
    
    public static GraphDb createTempDb (String prefix,
                                        String suffix, File temp)
        throws IOException {
        File junk = File.createTempFile(prefix, suffix, temp);
        File parent = temp == null ? junk.getParentFile() : temp;
        junk.delete();
        junk = new File (parent, junk.getName());
        junk.mkdirs();
        GraphDb graphDb = new GraphDb (junk);
        INSTANCES.put(junk, graphDb);
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
    
    public static synchronized GraphDb getInstance
        (File dir, CacheFactory cache) throws IOException {
        GraphDb gdb = INSTANCES.get(dir);
        if (gdb == null) {
            INSTANCES.put(dir, gdb = new GraphDb (dir, cache));
        }
        return gdb;
    }

    public static GraphDb getInstance (GraphDatabaseService gdb) {
        for (GraphDb db : INSTANCES.values())
            if (db.graphDb() == gdb)
                return db;
        return null;
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
