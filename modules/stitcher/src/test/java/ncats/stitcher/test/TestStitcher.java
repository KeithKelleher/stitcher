package ncats.stitcher.test;

import java.util.*;
import java.io.*;
import java.lang.reflect.Array;
import java.util.logging.Logger;
import java.util.logging.Level;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TestName;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import ncats.stitcher.*;
import ncats.stitcher.impl.MoleculeEntityFactory;
import static ncats.stitcher.StitchKey.*;

import chemaxon.struc.Molecule;
import chemaxon.util.MolHandler;
import chemaxon.formats.MolImporter;

/*
 * to run all tests here
 *   sbt stitcher/"testOnly ncats.stitcher.test.TestStitcher"
 */
public class TestStitcher {
    static final Logger logger =
        Logger.getLogger(TestStitcher.class.getName());

    static Object getValue (JsonNode n) {
        Object val = null;
        if (n.isArray()) {
            List<String> vals = new ArrayList<>();
            for (int i = 0; i < n.size(); ++i)
                vals.add(n.get(i).asText());
            if (vals.isEmpty()) {
            }
            else if (vals.size() == 1) {
                val = vals.get(0);
            }
            else {
                val = vals.toArray(new String[0]);
            }
        }
        else {
            val = n.asText();
        }
        return val;
    }

    static Map<String, Object> putValue (Map<String, Object> data,
                                         String field, Object value) {
        Object old = data.get(field);
        data.put(field, old != null ? Util.merge(old, value) : value);
        return data;
    }

    /*
     * json format generated by endpoint /stitches/v_{version}/{id}.simple
     * where {id} is the sgroup node id
     */
    static void registerSimpleJsonSource (String name, InputStream is, int ncomps,
                                          int[] stitches) throws Exception {
        ObjectMapper mapper = new ObjectMapper ();
        ObjectNode json = (ObjectNode) mapper.readTree(is);
        try (EntityRegistry reg = new EntityRegistry
             (GraphDb.createTempDb(name)) {
                @Override
                protected void init () {
                    super.init();
                    setIdField ("id");
                    setStrucField ("_SMILES");
                    add (I_UNII, "_UNII");
                    add (N_Name, "_SYNONYMS");
                    add (I_CODE, "_CODES");
                    add (I_CAS, "_CAS");
                    add (I_ChEMBL, "_ChEMBL");
                    add (H_InChIKey, "_INCHIKEY");
                }
            }) {
            reg.setDataSource(reg.getDataSourceFactory().register(name));
            
            assertTrue ("Not a valid simpl json format!",
                        json.has("data") && json.get("data").isArray());
            ArrayNode data = (ArrayNode) json.get("data");
            int count = json.get("count").asInt();
            assertTrue ("Expecting "+count+" records but instead got "+data.size(),
                        count == data.size());

            Map<Entity, String[]> activeMoieties = new HashMap<>();
            for (int i = 0; i < data.size(); ++i) {
                JsonNode node = data.get(i);
                Map<String, Object> dmap = new TreeMap<>();
                for (Iterator<String> iter = node.fieldNames(); iter.hasNext(); ) {
                    String fname = iter.next();
                    JsonNode fval = node.get(fname);
                    switch (fname) {
                    case "UNII":
                    case "Unii":
                    case "unii":
                    case "CompoundUNII":
                        putValue(dmap, "_UNII", getValue (fval));
                        break;
                        
                    case "Synonyms":
                    case "synonym":
                    case "hasRelatedSynonyms":
                    case "generic_name":
                    case "CompoundName":
                    case "label":
                    case "name":
                    case "broad_name":
                    case "CompoundSynonym":
                    case "PreferredName":
                        putValue (dmap, "_SYNONYMS", getValue (fval));
                        break;

                    case "CAS":
                    case "Cas":
                    case "cas":
                        putValue (dmap, "_CAS", getValue (fval));
                        break;

                    case "inchikey":
                        putValue (dmap, "_INCHIKEY", getValue (fval));
                        break;

                    case "smiles":
                    case "SMILES":
                    case "Smiles":
                        dmap.put("_SMILES", getValue (fval));
                        break;
                    }
                    putValue (dmap, fname, getValue (fval));
                }
                
                Entity e = reg.register(dmap);
                if (e != null && dmap.containsKey("ActiveMoieties")) {
                    Object am = dmap.get("ActiveMoieties");
                    Set<String> uniis = new HashSet<>();
                    if (am.getClass().isArray()) {
                        for (int k = 0; k < Array.getLength(am); ++k)
                            uniis.add((String) Array.get(am, k));
                    }
                    else {
                        uniis.add((String)am);
                    }
                    activeMoieties.put(e, uniis.toArray(new String[0]));
                }
            }

            for (Map.Entry<Entity, String[]> me : activeMoieties.entrySet()) {
                Entity ent = me.getKey();
                for (String u : me.getValue()) {
                    int cnt = 0;
                    for (Iterator<Entity> iter = reg.find(I_UNII, u);
                         iter.hasNext(); ++cnt) {
                        Entity e = iter.next();
                        if (!ent.equals(e))
                            ent.stitch(e, R_activeMoiety, u);
                    }

                    assertTrue ("Active moiety "+u+" not found!", cnt > 0);
                }
            }

            int n = reg.count(AuxNodeType.ENTITY).intValue();
            assertTrue ("Expecting "+count+" entities but instead got "
                        +n, n == count);

            DataSource ds = reg.getDataSourceFactory()
                .register("stitch_v1");
            
            List<Long> comps = new ArrayList<>();
            int nc = reg.components(comps);
            assertTrue ("Expect "+ncomps+" component(s) but instead got "+nc,
                        nc == ncomps);
            for (Long id : comps) {
                Component comp = reg.component(id);
                reg.untangle(new UntangleCompoundComponent (ds, comp));
            }
            
            n = reg.count(ds.getLabel()).intValue();
            assertTrue ("Expect "+stitches.length
                        +" stitch node(s) but instead got "+n,
                        n == stitches.length);
            
            final Set<Integer> sizes = new TreeSet<>();
            for (int i = 0; i < stitches.length; ++i)
                sizes.add(stitches[i]);
            reg.maps(e -> {
                    Stitch s = Stitch.getStitch(e);
                    logger.info("@@@@@ stitch node "+s.getId()
                                +" of size "+s.size());
                    assertTrue ("Stitch size "+s.size()+" is not expected: "
                                + sizes, sizes.contains(s.size()));
                }, "SGROUP");
        }
    }
    
    void testMergedStitches (String name, int[] stitches, Double threshold,
                             InputStream... streams)
        throws Exception {
        ObjectMapper mapper = new ObjectMapper ();
        ArrayNode data = mapper.createArrayNode();
        int ncomp = stitches.length;

        int total = 0;
        for (InputStream is : streams) {
            try {
                JsonNode json = mapper.readTree(is);
                total += json.get("count").asInt();
                ArrayNode an = (ArrayNode) json.get("data");
                for (int i = 0; i < an.size(); ++i)
                    data.add(an.get(i));
            }finally{
                is.close();
            }
        }
        assertTrue ("Expecting json to contain 6 elements in data but "
                    +"instead got "+data.size(), data.size() == total);

        TestRegistry reg = new TestRegistry (name);
        try {
            reg.register(data);
            /*
              reg.traverse((a, b, values) -> {
              logger.info("### ("+a.getId()+","+b.getId()+") => "+values);
              return true;
              });
            */
            
            long count = reg.count(AuxNodeType.ENTITY);
            assertTrue ("Expecting "+data.size()+" entities but instead got "
                        +count, data.size() == count);
            
            DataSource ds = reg.getDataSourceByVersion(1);
            
            List<Long> comps = new ArrayList<>();
            int nc = reg.components(comps);
            assertTrue ("Expect 1 component but instead got "+nc, nc == 1);
            for (Long id : comps) {
                Component comp = reg.component(id);
                reg.untangle(new UntangleCompoundComponent (ds, comp));
            }
            
            //reg.untangle(new UntangleCompoundStitches (ds, threshold));
            
            count = reg.count(ds.getLabel());
            assertTrue ("Expect "+ncomp
                        +" stitch node(s) but instead got "+count,
                        count == ncomp);
            final Set<Integer> sizes = new TreeSet<>();
            for (int i = 0; i < stitches.length; ++i)
                sizes.add(stitches[i]);
            reg.maps(e -> {
                    Stitch s = Stitch.getStitch(e);
                    logger.info("@@@@@ stitch node "+s.getId()
                                +" of size "+s.size());
                    assertTrue ("Stitch size "+s.size()+" is not expected: "
                                + sizes, sizes.contains(s.size()));
                }, "SGROUP");
        }
        finally {
            reg.shutdown();
        }
    }

    @Rule public TestName name = new TestName();
    public TestStitcher () {
    }
    /*
    @Test
    public void testStitch01 () throws Exception {
        logger.info("##################################### "
                    +name.getMethodName());
        testMergedStitches
            (name.getMethodName(), new int[]{6}, null,
             EntityRegistry.class.getResourceAsStream("/1JQS135EYN.json"));
    }

    @Test
    public void testStitch02 () throws Exception {
        logger.info("##################################### "
                    +name.getMethodName());
        //5 [1,3,6,8,10]
        testMergedStitches
            (name.getMethodName(), new int[]{5}, null,
             EntityRegistry.class.getResourceAsStream("/cefotetan1.json"),
             EntityRegistry.class.getResourceAsStream("/cefotetan2.json"));
    }

    @Test
    public void testStitch03 () throws Exception {
        logger.info("##################################### "
                    +name.getMethodName());
        //9 [1,3,6,8,10,12,14,16,18]
        testMergedStitches
            (name.getMethodName(), new int[]{9}, null,
             EntityRegistry.class.getResourceAsStream("/OZAGREL1.json"),
             EntityRegistry.class.getResourceAsStream("/OZAGREL2.json"));
    }

    @Test
    public void testStitch04 () throws Exception {
        logger.info("##################################### "
                    +name.getMethodName());
        //10 [3,18,26,28,30,32,38,40,42,44]
        //6 [1,8,12,16,22,36]
        //6 [6,10,14,20,24,34]
        testMergedStitches
            (name.getMethodName(), new int[]{10,6,6}, null,
             EntityRegistry.class.getResourceAsStream("/1020343.json"));
    }

    @Test
    public void testStitch05 () throws Exception {
        logger.info("##################################### "
                    +name.getMethodName());
        //12 [10,13,15,28,44,66,74,76,82,84,88,92]
        //5 [1,5,68,72,94]
        //4 [21,42,80,100]
        //4 [34,36,78,102]
        //4 [52,64,86,96]
        //3 [8,70,106]
        //3 [40,46,108]
        //3 [48,50,90]
        //3 [54,56,104]
        //3 [58,60,98]
        //2 [3,38]
        //2 [17,19]
        //2 [24,26]
        //2 [30,32]
        //1 [62]
        testMergedStitches
            (name.getMethodName(), new int[]{12, 5, 4, 4, 4, 3, 3,
                                             3, 3, 3, 2, 2, 2, 2, 1}, null,
             EntityRegistry.class.getResourceAsStream("/heparin.json"));
    }
    */
    @Test
    public void testStitch06 () throws Exception {
        logger.info("##################################### "
                    +name.getMethodName());
        //14 [5,13,19,39,41,49,51,53,61,65,71,85,87,95]
        //12 [3,17,21,25,35,57,67,73,77,79,83,89]
        //7 [8,11,37,59,75,81,91]
        //3 [15,43,69]
        //3 [23,33,63]
        //2 [1,93]
        //2 [27,45]
        //2 [29,47]
        //2 [31,55]        
        testMergedStitches
            (name.getMethodName(), new int[]{14, 12, 7, 3, 3, 2, 2, 2, 2}, null,
             EntityRegistry.class.getResourceAsStream("/2991.json"));
    }
    /*
    @Test
    public void testStitch07 () throws Exception {
        logger.info("##################################### "
                    +name.getMethodName());
        testMergedStitches
            (name.getMethodName(), 30, null,
             EntityRegistry.class.getResourceAsStream("/12871.json"));
    }
    */
    /*
    @Test
    public void testStitch08 () throws Exception {
        logger.info("##################################### "
                    +name.getMethodName());
        testMergedStitches
            (name.getMethodName(), new int[]{13, 7, 4}, null,
             EntityRegistry.class.getResourceAsStream("/69312.json"));
    }

    @Test
    public void testStitch09 () throws Exception {
        logger.info("##################################### "
                    +name.getMethodName());
        MolImporter molimp = new MolImporter
            (EntityRegistry.class.getResourceAsStream("/simple_l3.smi"));
        try (MoleculeEntityFactory reg = new MoleculeEntityFactory
             (GraphDb.createTempDb(name.getMethodName())) {
                @Override
                protected void init () {
                    super.init();
                    setIdField ("field_0");
                }
            }) {
            reg.setDataSource(reg.getDataSourceFactory()
                              .register(name.getMethodName()));
            int n = 0;
            for (Molecule mol = new Molecule (); molimp.read(mol); ) {
                logger.info("### registering "+mol.getProperty("field_0"));
                Entity e = reg.register(mol);
                if (e != null)
                    ++n;
            }
            long count = reg.count(AuxNodeType.ENTITY);
            assertTrue ("Expecting "+n+" entities but instead got "
                        +count, n == count); 
            DataSource ds = reg.getDataSourceFactory()
                .register("stitch_v1");
            
            List<Long> comps = new ArrayList<>();
            int nc = reg.components(comps);
            assertTrue ("Expect 1 component but instead got "+nc, nc == 1);
            for (Long id : comps) {
                Component comp = reg.component(id);
                reg.untangle(new UntangleCompoundComponent (ds, comp));
            }
            
            int ncomp = 2; // should be 2 components
            count = reg.count(ds.getLabel());
            assertTrue ("Expect "+ncomp
                        +" stitch node(s) but instead got "+count,
                        count == ncomp);
        }
    }
    
    @Test
    public void testStitch10 () throws Exception {
        logger.info("##################################### "
                    +name.getMethodName());
        registerSimpleJsonSource
            (name.getMethodName(),
             EntityRegistry.class.getResourceAsStream("/bicalutamide.json"), 1,
             // expect two stitches of sizes 3 and 10; we should also check
             // individual members?
             // 10 [1,6,8,10,12,14,16,22,24,26]
             // 3 [3,18,20]
             new int[]{3, 10});
    }
    */
}
