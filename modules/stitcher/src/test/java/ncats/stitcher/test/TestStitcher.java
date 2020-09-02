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
 * to run all test here
 *   sbt stitcher/"testOnly ncats.stitcher.test.TestStitcher"
 */
public class TestStitcher {
    static final Logger logger =
        Logger.getLogger(TestStitcher.class.getName());

    void testMergedStitches (String name, int ncomp, Double threshold,
                             InputStream... streams)
        throws Exception {
        ObjectMapper mapper = new ObjectMapper ();
        ArrayNode data = mapper.createArrayNode();

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
        }
        finally {
            reg.shutdown();
        }
    }

    @Rule public TestName name = new TestName();
    public TestStitcher () {
    }

    @Test
    public void testStitch1 () throws Exception {
        logger.info("##################################### "
                    +name.getMethodName());
        testMergedStitches
            (name.getMethodName(), 1, null,
             EntityRegistry.class.getResourceAsStream("/1JQS135EYN.json"));
    }

    @Test
    public void testStitch2 () throws Exception {
        logger.info("##################################### "
                    +name.getMethodName());
        testMergedStitches
            (name.getMethodName(), 1, null,
             EntityRegistry.class.getResourceAsStream("/cefotetan1.json"),
             EntityRegistry.class.getResourceAsStream("/cefotetan2.json"));
    }

    @Test
    public void testStitch3 () throws Exception {
        logger.info("##################################### "
                    +name.getMethodName());
        testMergedStitches
            (name.getMethodName(), 1, null,
             EntityRegistry.class.getResourceAsStream("/OZAGREL1.json"),
             EntityRegistry.class.getResourceAsStream("/OZAGREL2.json"));
    }

    @Test
    public void testStitch4 () throws Exception {
        logger.info("##################################### "
                    +name.getMethodName());
        testMergedStitches
            (name.getMethodName(), 3, null,
             EntityRegistry.class.getResourceAsStream("/1020343.json"));
    }

    /*
    @Test
    public void testStitch5 () throws Exception {
        logger.info("##################################### "
                    +name.getMethodName());
        testMergedStitches
            (name.getMethodName(), 16, null,
             EntityRegistry.class.getResourceAsStream("/heparin.json"));
    }

    @Test
    public void testStitch6 () throws Exception {
        logger.info("##################################### "
                    +name.getMethodName());
        testMergedStitches
            (name.getMethodName(), 13, null,
             EntityRegistry.class.getResourceAsStream("/2991.json"));
    }

    @Test
    public void testStitch7 () throws Exception {
        logger.info("##################################### "
                    +name.getMethodName());
        testMergedStitches
            (name.getMethodName(), 30, null,
             EntityRegistry.class.getResourceAsStream("/12871.json"));
    }

    @Test
    public void testStitch8 () throws Exception {
        logger.info("##################################### "
                    +name.getMethodName());
        testMergedStitches
            (name.getMethodName(), 6, null,
             EntityRegistry.class.getResourceAsStream("/69312.json"));
    }
    */

    @Test
    public void testStitch9 () throws Exception {
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
}
