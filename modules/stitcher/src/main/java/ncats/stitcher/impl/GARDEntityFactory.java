package ncats.stitcher.impl;

import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.Callable;
import java.lang.reflect.Array;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.sql.*;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Transaction;

import ncats.stitcher.graph.UnionFind;
import ncats.stitcher.*;
import static ncats.stitcher.StitchKey.*;

/*
 * sbt stitcher/"runMain ncats.stitcher.impl.GARDEntityFactory DBDIR"
 */
public class GARDEntityFactory extends EntityRegistry {
    static final Logger logger =
        Logger.getLogger(GARDEntityFactory.class.getName());

    static final String DEFAULT_JDBC_AUTH =
        "integratedSecurity=true;authenticationScheme=JavaKerberos";
    static final String GARD_JDBC =
        "jdbc:sqlserver://ncatswnsqldvv02.nih.gov;databaseName=ORDRGARD_DEV;";

    class UntangleComponent {
        final public Component comp;
        final public Map<StitchKey, Map<Object, Set<Long>>> values =
            new LinkedHashMap<>();
        final public PrintStream ps;

        UntangleComponent (Component comp, StitchKey... stitches) {
            this (null, comp, stitches);
        }
        
        UntangleComponent (PrintStream ps,
                           Component comp, StitchKey... stitches) {
            if (stitches == null || stitches.length == 0)
                throw new IllegalArgumentException ("keys must not be empty!");
            
            List keys = new ArrayList ();            
            for (StitchKey key : stitches) {
                final Map<Object, Integer> stats = comp.stats(key);
                List order = new ArrayList (stats.keySet());
                Collections.sort(order, (a,b) -> stats.get(b) - stats.get(a));
                if (!stats.isEmpty()) {
                    Map<Object, Set<Long>> map = values.get(key);
                    if (map == null)
                        values.put(key, map = new LinkedHashMap<>());
                    for (Object k : order) {
                        Component c = comp.filter(key, k);
                        map.put(k, c.nodeSet());
                    }
                }
            }
            this.comp = comp;
            this.ps = ps == null ? System.out : ps;
        }

        public void mergeStitches () {
            List keys = new ArrayList ();
            Map<Integer, Component> map = new TreeMap<>();
            
            UnionFind uf = new UnionFind ();
            for (Map.Entry<StitchKey, Map<Object, Set<Long>>> me
                     : values.entrySet()) {
                ps.println(".. ["+me.getKey()+"]");
                for (Map.Entry<Object, Set<Long>> ve
                         : me.getValue().entrySet()) {
                    Component c = getComponent
                        (ve.getValue().toArray(new Long[0]));
                    Map<Object, Integer> stats = c.stats(me.getKey());
                    ps.println("....\""+ve.getKey()+"\"="
                               +stats.get(ve.getKey())+"/"
                               +stitchCount (me.getKey(), ve.getKey())
                               +" "+c.getId()+" ("+c.size()+") "+c.nodeSet());
                    int q = keys.size();
                    for (Map.Entry<Integer, Component> qe : map.entrySet()) {
                        double sim = c.similarity(qe.getValue());
                        if (sim > 0.) {
                            ps.println
                                ("     "+String.format("%1$.3f", sim)
                                 +" \""+keys.get(qe.getKey())+"\"");
                            if (sim > 0.4) {
                                uf.union(qe.getKey(), q);
                            }
                        }
                    }
                    map.put(q, c);
                    keys.add(ve.getKey());
                }
                ps.println();
            }

            long[][] ccs = uf.components();
            ps.println("**** "+ccs.length+" component(s)!");
            Map<Long, BitSet> hc = new TreeMap<>();
            for (int n = 0; n < ccs.length; ++n) {
                long[] c = ccs[n];
                ps.println("### component "+n+" ("+c.length+")");
                for (int i = 0; i < c.length; ++i) {
                    int ki = (int)c[i];
                    Object k = keys.get(ki);
                    Component kc = map.get(ki);
                    ps.println("......"+k+" "+kc.nodeSet());
                    
                    for (Long id : kc.nodeSet()) {
                        BitSet bs = hc.get(id);
                        if (bs == null)
                            hc.put(id, bs = new BitSet (ccs.length));
                        bs.set(n);
                    }
                }
            }
            ps.println("*** "+hc);
        }

        public void mergeEntities () {
        }
    }

    static class GARD implements AutoCloseable {
        PreparedStatement pstm1, pstm2, pstm3, pstm4;
        
        Map<Integer, String> idtypes = new TreeMap<>();
        Map<Integer, String> diseasetypes = new TreeMap<>();
        Map<Integer, String> resources = new TreeMap<>();
        
        GARD (Connection con) throws SQLException {
            pstm1 = con.prepareStatement
                ("select * from RD_tblDiseaseIdentifiers "
                 +"where DiseaseID=? and IsActive = 1");
            pstm2 = con.prepareStatement
                ("select * from rd_tbldiseaselevel where DiseaseID = ?");
            pstm3 = con.prepareStatement
                ("select a.Question,a.Answer,c.ResourceClassificationName "
                 +"from TBLQuestion a, ASCQuestionClassification b, "
                 +"RD_tblResourceClassification c,vw_DiseaseQuestions d "
                 +"where a.QuestionID=d.QuestionID "
                 +"and a.QuestionID=b.QuestionID "
                 +"and b.ResourceClassificationID=c.ResourceClassificationID "
                 +"and d.DiseaseID=?");
            pstm4 = con.prepareStatement
                ("select * from RD_ascDiseaseTypes where DiseaseID=?");
            
            Statement stm = con.createStatement();
            ResultSet rset = stm.executeQuery
                ("select * from RD_luIdentifierType");
            while (rset.next()) {
                int id = rset.getInt("IdentifierTypeID");
                String type = rset.getString("IdentifierTypeText");
                idtypes.put(id, type);
            }
            rset.close();
            
            rset = stm.executeQuery("select * from RD_luDiseaseType");
            while (rset.next()) {
                int id = rset.getInt("DiseaseTypeID");
                String type = rset.getString("DiseaseTypeName");
                diseasetypes.put(id, type);
            }
            rset.close();

            rset = stm.executeQuery
                ("select * from RD_tblResourceClassification");
            while (rset.next()) {
                int id = rset.getInt("ResourceClassificationID");
                String name = rset.getString("ResourceClassificationName");
                resources.put(id, name);
            }
            rset.close();
        }

        public Map<String, Object> instrument (long id) throws SQLException {
            Map<String, Object> data = new TreeMap<>();
            data.put("gard_id", format (id));
            data.put("id", id);
            identifiers (data);
            categories (data);
            resources (data);
            relationships (data);
            return data;
        }

        Map<String, Object> identifiers (Map<String, Object> data)
            throws SQLException {
            pstm1.setLong(1, (Long)data.get("id"));
            
            ResultSet rset = pstm1.executeQuery();
            List<String> xrefs = new ArrayList<>();
            List<String> synonyms = new ArrayList<>();
            while (rset.next()) {
                int tid = rset.getInt("IdentifierTypeID");
                String value = rset.getString("DiseaseIdentifier");
                String type = idtypes.get(tid);
                if (type != null) {
                    if ("synonym".equalsIgnoreCase(type)) {
                        synonyms.add(value);
                    }
                    else {
                        if ("orphanet".equalsIgnoreCase(type)) {
                            // make sure we have ORPHA:XXX and ORPHANET:XXX 
                            xrefs.add("ORPHA:"+value); 
                        }
                        xrefs.add(type.toUpperCase()+":"+value);
                    }
                }
                else {
                    logger.warning("Unknown identifier type: "+tid);
                }
            }
            rset.close();
            
            if (!xrefs.isEmpty())
                data.put("xrefs", xrefs.toArray(new String[0]));
            
            if (!synonyms.isEmpty())
                data.put("synonyms", synonyms.toArray(new String[0]));
            
            return data;
        }

        Map<String, Object> categories (Map<String, Object> data)
            throws SQLException {
            pstm4.setLong(1, (Long)data.get("id"));
            ResultSet rset = pstm4.executeQuery();
            List<String> categories = new ArrayList<>();
            while (rset.next()) {
                int id = rset.getInt("DiseaseTypeID");
                String type = diseasetypes.get(id);
                if (type != null) {
                    categories.add(type);
                }
            }
            rset.close();
            if (!categories.isEmpty()) {
                data.put("categories", categories.size() == 1
                         ? categories.get(0)
                         : categories.toArray(new String[0]));
            }
            return data;
        }

        Map<String, Object> resources (Map<String, Object> data)
            throws SQLException {
            pstm3.setLong(1, (Long)data.get("id"));
            ResultSet rset = pstm3.executeQuery();
            Map<String, List<String>> ans = new TreeMap<>();
            while (rset.next()) {
                String res = rset.getString("ResourceClassificationName");
                List<String> vals = ans.get(res);
                if (vals == null)
                    ans.put(res, vals = new ArrayList<>());
                vals.add(rset.getString("Answer"));
            }
            rset.close();
            
            for (Map.Entry<String, List<String>> me : ans.entrySet()) {
                List<String> vals = me.getValue();
                if (vals.size() == 1) {
                    data.put(me.getKey(), vals.get(0));
                }
                else {
                    data.put(me.getKey(), vals.toArray(new String[0]));
                }
            }
            
            return data;
        }

        Map<String, Object> relationships (Map<String, Object> data)
            throws SQLException {
            pstm2.setLong(1, (Long)data.get("id"));
            ResultSet rset = pstm2.executeQuery();
            Set<Long> parents = new TreeSet<>();
            while (rset.next()) {
                long parent = rset.getLong("ParentDiseaseID");
                if (!rset.wasNull())
                    parents.add(parent);
            }
            rset.close();
            
            if (!parents.isEmpty()) {
                if (parents.size() == 1)
                    data.put("parents", parents.iterator().next());
                else
                    data.put("parents", parents.toArray(new Long[0]));
            }
            return data;
        }
        
        public void close () throws Exception {
            pstm1.close();
            pstm2.close();
            pstm3.close();
            pstm4.close();
        }
    } // GARD

    static String format (long id) {
        return String.format("GARD:%1$07d", id);
    }

    static class UMLS {
        public final JsonNode json;
        
        UMLS (String name) throws Exception {
            URL url = new URL
                ("https://blackboard.ncats.io/ks/umls/api/concepts/"
                 +name.replaceAll(" ", "%20"));
            URLConnection con = url.openConnection();
            ObjectMapper mapper = new ObjectMapper ();
            json = mapper.readTree(con.getInputStream());
        }
    }
    
    public GARDEntityFactory (GraphDb graphDb) throws IOException {
        super (graphDb);
    }
    public GARDEntityFactory (String dir) throws IOException {
        super (dir);
    }
    public GARDEntityFactory (File dir) throws IOException {
        super (dir);
    }

    @Override
    protected void init () {
        super.init();
        setIdField ("gard_id");
        setNameField ("name");
        add (N_Name, "name")
            .add(N_Name, "synonyms")
            .add(I_CODE, "gard_id")
            .add(I_CODE, "xrefs")
            .add(T_Keyword, "categories")
            .add(T_Keyword, "sources")
            ;
    }
        
    public DataSource register (Connection con, int version)
        throws Exception {
        DataSource ds = getDataSourceFactory().register("GARD_v"+version);
        
        Statement stm = con.createStatement();        
        Integer size = (Integer)ds.get(INSTANCES);
        if (size != null && size > 0) {
            logger.info(ds.getName()+" ("+size
                        +") has already been registered!");
            return ds;
        }
        
        setDataSource (ds);
        try (ResultSet rset = stm.executeQuery
             ("select * from RD_tblDisease where StatusID=4 and isSpanish=0 "
              //+"and isRare = 1"
              );
             GARD gard = new GARD (con)) {
            int count = 0;
            Map<Long, Entity> entities = new HashMap<>();
            Map<Long, Object> parents = new HashMap<>();
            for (; rset.next(); ++count) {
                long id = rset.getLong("DiseaseID");
                String name = rset.getString("DiseaseName");
                
                Map<String, Object> data = gard.instrument(id);
                data.put("name", name);
                data.put("is_rare", rset.getInt("isRare") == 1);
                
                Entity ent = register (data);
                logger.info
                    ("+++++ "+data.get("id")+": "+name+" ("+ent.getId()+")");
                if (data.containsKey("parents")) {
                    parents.put(id, data.get("parents"));
                }
                entities.put(id, ent);
            }
            
            // now setup relationships
            for (Map.Entry<Long, Object> me : parents.entrySet()) {
                Entity ent = entities.get(me.getKey());
                Object pval = me.getValue();
                if (pval.getClass().isArray()) {
                    int len = Array.getLength(pval);
                    for (int i = 0; i < len; ++i) {
                        Long id = (Long)Array.get(pval, i);
                        Entity p = (Entity)entities.get(id);
                        if (p != null) {
                            if (!ent.equals(p))
                                ent.stitch(p, R_subClassOf, format (id));
                        }
                        else
                            logger.warning("Can't find parent entity: "+id);
                    }
                }
                else {
                    Long id = (Long)pval;
                    Entity p = (Entity)entities.get(id);
                    if (p != null) {
                        if (!ent.equals(p))
                            ent.stitch(p, R_subClassOf, format (id));
                    }
                    else
                        logger.warning("Can't find parent entity: "+id);
                }
            }
            logger.info(count+" entities registered!");
            ds.set(INSTANCES, count);
            updateMeta (ds);
        }
        
        return ds;
    }

    public void checkGARD (int version) {
        List<Entity> notmatched = new ArrayList<>();
        int count = maps (e -> {
                int c = dump (e);
                if (c == 0)
                    notmatched.add(e);
            }, "GARD_v"+version);
        
        logger.info("####### "+count+" entries; "+notmatched.size()
                    +" entries has no neighbors!");
        resolveUMLS (System.out, notmatched);
    }

    int calcScore (Map<StitchKey, Object> sv) {
        int score = 0;
        for (Map.Entry<StitchKey, Object> me : sv.entrySet()) {
            StitchKey key = me.getKey();
            Object value = me.getValue();
            switch (key) {
            case I_CODE:
                if (value.getClass().isArray()) {
                    score += 10*Array.getLength(value);
                }
                else {
                    score += 10;
                }
                break;
                
            case N_Name:
                if (value.getClass().isArray()) {
                    int len = Array.getLength(value);
                    for (int i = 0; i < len; ++i)
                        score += ((String)Array.get(value, i)).length();
                }
                else {
                    score += ((String)value).length();
                }
                break;
            }
        }
        return score;
    }

    public void showComponents () {
        final Label[] sources = {
            Label.label("GHR_v1"),
            Label.label("DOID.owl.gz"),
            Label.label("GARD_v1"),
            Label.label("MESH.ttl.gz"),
            Label.label("MEDLINEPLUS.ttl.gz"),
            Label.label("MESH.ttl.gz"),
            Label.label("MONDO.owl.gz"),
            Label.label("OMIM.ttl.gz"),
            Label.label("ordo.owl.gz")
        };

        final Label[] types = {
            Label.label("T028"), // gngm - gene or genome
            Label.label("T033"), // finding
            Label.label("T116"), // Amino Acid, Peptide, or Protein
            Label.label("T123"), // Biologically Active Substance
            Label.label("T129"), // Immunologic Factor
            Label.label("T086"), // Nucleotide Sequence
            Label.label("T126"), // Enzyme
            Label.label("T131"), // Hazardous or Poisonous Substance
            Label.label("T191"), // Neoplastic Process
            Label.label("T109"), // Organic Chemical
            Label.label("T121"), // Pharmacologic Substance
            Label.label("T046"), // Pathologic Function
            Label.label("T125"), // Hormone
            Label.label("T192"), // Receptor
            Label.label("T048"), // Mental or Behavioral Dysfunction
        };

        Predication rule1 = new Predication () {
                public int score
                    (Entity source, Map<StitchKey, Object> sv, Entity target) {
                    int score = 0;
                    if (!"deprecated".equals(source._get(STATUS))
                        && !"deprecated".equals(target._get(STATUS))
                        && source._hasAnyLabels(sources)
                        && target._hasAnyLabels(sources)
                        && !source._hasAnyLabels(types)
                        && !target._hasAnyLabels(types)) {
                        score = calcScore (sv);
                    }
                    return score;
                }
            };

        logger.info("######### generating strongly connected components...");
        for (Iterator<Component> it = connectedComponents(rule1);
             it.hasNext();) {
            Component comp = it.next();
            System.out.println("--");
            System.out.println("++ component "+comp.size()+": "+comp.nodeSet());
            //dump (System.out, comp);
            UntangleComponent uc = new UntangleComponent (comp, N_Name, I_CODE);
            uc.mergeStitches();
            System.out.println();
        }
    }

    void dump (OutputStream os, Component comp) {
        PrintStream ps = new PrintStream (os);
        ps.println("## "+comp.labels());

        /*
        Entity[] entities = comp.entities();
        final Map<String, Double> simmat = new HashMap<>();
        for (int i = 0; i < entities.length; ++i) {
            for (int j = i+1; j < entities.length; ++j) {
                String key = "["
                    +(entities[i].getId() < entities[j].getId()
                      ? entities[i].getId()+","+entities[j].getId()
                      : entities[j].getId()+","+entities[i].getId())
                    +"]";
                simmat.put(key, entities[i].similarity(entities[j]));
            }
        }
        List<String> pairs = new ArrayList<>(simmat.keySet());
        Collections.sort(pairs, (a,b) -> {
                double s0 = simmat.get(a);
                double s1 = simmat.get(b);
                if (s1 > s0) return 1;
                if (s1 < s0) return -1;
                return a.compareTo(b);
            });
        for (String k : pairs) {
            ps.println(".. "+String.format("%1$.3f", simmat.get(k))+" "+k);
        }
        */
        
        Map<Object, Component> values = new LinkedHashMap<>();
        List keys = new ArrayList ();
        UnionFind uf = new UnionFind ();
        for (StitchKey key : Entity.KEYS) {
            final Map<Object, Integer> stats = comp.stats(key);
            if (!stats.isEmpty()) {
                ps.println(".. ["+key+"]");
                List order = new ArrayList (stats.keySet());
                Collections.sort(order, (a,b) -> stats.get(b) - stats.get(a));
                for (Object k : order) {
                    Component c = comp.filter(key, k);
                    ps.println("....\""+k+"\"="+stats.get(k)+"/"
                               +stitchCount (key, k)
                               +" "+c.getId()+" ("+c.size()+") "+c.nodeSet());
                    switch (key) {
                    case I_CODE:
                    case N_Name:
                        {   int q = keys.size();
                            for (Map.Entry<Object, Component> me
                                     : values.entrySet()) {
                                double sim = c.similarity(me.getValue());
                                if (sim > 0.) {
                                    ps.println
                                        ("     "+String.format("%1$.3f", sim)
                                         +" \""+me.getKey()+"\"");
                                    if (sim >= .4) { 
                                        int p = keys.indexOf(me.getKey());
                                        
                                        if (p < 0) {
                                            logger.warning
                                                ("BOGUS KEY: "+me.getKey());
                                        }
                                        else {
                                            uf.union(p, q);
                                        }
                                    }
                                }
                            }

                            keys.add(k);
                            values.put(k, c);
                        }
                    }
                }
                ps.println();
            }
        }
        
        long[][] ccs = uf.components();
        ps.println("**** "+ccs.length+" component(s)!");
        Map<Long, BitSet> hc = new TreeMap<>();
        for (int n = 0; n < ccs.length; ++n) {
            long[] c = ccs[n];
            ps.println("### component "+n+" ("+c.length+")");
            for (int i = 0; i < c.length; ++i) {
                Object k = keys.get((int)c[i]);
                Component kc = values.get(k);
                ps.println("......"+k+" "+kc.nodeSet());
            
                for (Long id : kc.nodeSet()) {
                    BitSet bs = hc.get(id);
                    if (bs == null)
                        hc.put(id, bs = new BitSet (ccs.length));
                    bs.set(n);
                }
            }
        }
        ps.println("*** "+hc);
    }

    JsonNode resolveUMLS (String name) throws Exception {
        JsonNode json = null;
        UMLS umls = new UMLS (name);
        if (umls.json.size() == 1 && !umls.json.has("score")) {
            json = umls.json.get(0).get("concept");
        }
        return json;
    }

    void resolveUMLS (OutputStream os, Collection<Entity> entities) {
        PrintStream ps = new PrintStream (os);
        ps.println("GARD ID\tDisease Name\tCUI\tType\tUMLS Concept");
        for (Entity e : entities) {
            Map<String, Object> data = e.payload();
            String name = (String) data.get("name");
            try {
                JsonNode json = resolveUMLS (name);
                if (json != null) {
                    ps.print(data.get("id")+"\t"+name);
                    ps.print("\t"+json.get("cui").asText()+"\t");
                    JsonNode types = json.get("semanticTypes");
                    for (int i = 0; i < types.size(); ++i) {
                        ps.print(types.get(i).get("name").asText());
                        if (i+1 < types.size())
                            ps.print(";");
                    }
                    ps.print("\t"+json.get("name").asText());
                    ps.println();
                }
            }
            catch (Exception ex) {
                logger.log(Level.SEVERE,
                           "Can't resolve GARD entity "+data.get("id"), ex);
            }
        }
    }

    int dump (Entity e) {
        Map<String, Object> data = e.payload();
        System.out.println(data.get("id")+": "+data.get("name"));
        Map<StitchKey, Object> keys = e.keys();
        for (Map.Entry<StitchKey, Object> me : keys.entrySet()) {
            System.out.print("   "+me.getKey());
            Object val = me.getValue();
            if (val.getClass().isArray()) {
                int len = Array.getLength(val);
                System.out.println(" "+len);
                for (int i = 0; i < len; ++i)
                    System.out.println("      "+Array.get(val, i));
            }
            else {
                System.out.println(" 1");
                System.out.println("      "+val);
            }
        }
        System.out.println();
        return keys.size();
    }

    public static class Register {
        public static void main (String[] argv) throws Exception {
            if (argv.length < 1) {
                logger.info("Usage: "+Register.class.getName()
                            +" DBDIR [user=USERNAME] [password=PASSWORD]"
                            +" [version=1,2,..]");
                System.exit(1);
            }

            int version = 1;
            String user = null, password = null;
            for (int i = 1; i < argv.length; ++i) {
                if (argv[i].startsWith("user=")) {
                    user = argv[i].substring(5);
                }
                else if (argv[i].startsWith("password=")) {
                    password = argv[i].substring(9);
                }
                else if (argv[i].startsWith("version=")) {
                    version = Integer.parseInt(argv[i].substring(8));
                }
            }

            String jdbcUrl = GARD_JDBC;
            if (user != null && password != null) {
                jdbcUrl += "user="+user+";password="+password;
                logger.info("#### using credential "+user);
            }
            else {
                jdbcUrl += DEFAULT_JDBC_AUTH;
                logger.info("#### using default credentials");
            }
            
            try (GARDEntityFactory gef = new GARDEntityFactory (argv[0]);
                 Connection con = DriverManager.getConnection(jdbcUrl)) {
                gef.register(con, version);
            }
        }
    }

    public static void main (String[] argv) throws Exception {
        if (argv.length == 0) {
            logger.info("Usage: "+GARDEntityFactory.class.getName()
                        +" DBDIR");
            System.exit(1);
        }
        
        try (GARDEntityFactory gef = new GARDEntityFactory (argv[0])) {
            //gef.checkGARD(1);
            gef.showComponents();
        }
    }
}
