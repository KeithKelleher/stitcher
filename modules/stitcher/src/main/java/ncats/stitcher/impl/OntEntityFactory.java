package ncats.stitcher.impl;

import java.io.*;
import java.net.URL;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.Callable;
import java.lang.reflect.Array;

import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.ontology.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.util.FileManager;
import org.apache.jena.util.iterator.ExtendedIterator;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Transaction;

import ncats.stitcher.*;
import static ncats.stitcher.StitchKey.*;

/**
 * sbt -Djdk.xml.entityExpansionLimit=0 stitcher/"runMain ncats.stitcher.impl.OntEntityFactory ordo.owl"
 */
public class OntEntityFactory extends EntityRegistry {
    static final Logger logger =
        Logger.getLogger(OntEntityFactory.class.getName());
    
    static final String[] _RELATIONS = {
        "subClassOf",
        "equivalentClass",
        "exactMatch",
        "closeMatch",
        "manifestation_of",
        "has_phenotype",
        "allelic_variant_of",
        "has_allelic_variant",
        "has_inheritance_type",
        "inheritance_type_of",
        "phenotype_of",
        "part_of",
        "mapped_to",
        "related_to"
    };
    static final Set<String> RELATIONS =
        new TreeSet<>(Arrays.asList(_RELATIONS));

    static class OntologyResource {
        final public Resource resource;
        final public String uri;
        final public String type;
        final public Map<String, Object> props = new TreeMap<>();
        final public Map<String, Object> links = new TreeMap<>();
        final public List<OntologyResource> axioms = new ArrayList<>();
        
        OntologyResource (Resource res) {
            String t = null;
            for (StmtIterator it = res.listProperties(); it.hasNext(); ) {
                Statement stm = it.next();
                Property prop = stm.getPredicate();
                RDFNode obj = stm.getObject();
                String pname = prop.getLocalName();
                if (obj.isResource()) {
                    Resource r = obj.asResource();
                    if ("type".equals(pname)) {
                        t = r.getLocalName();
                    }
                    Object old = links.get(pname);
                    links.put(pname, old != null ? Util.merge(old, r) : r);
                }
                else {
                    Object v = obj.asLiteral().getValue();
                    if (!v.getClass().isAssignableFrom(Number.class))
                        v = v.toString();
                    if (!"".equals(v)) {
                        Object old = props.get(pname);
                        props.put(pname, old != null ? Util.merge(old, v) : v);
                    }
                }
            }
            this.uri = res.getURI();
            this.type = t;
            this.resource = res;
        }

        public int hashCode () { return resource.hashCode(); }
        public boolean equals (Object obj) {
            if (obj instanceof OntologyResource) {
                return resource.equals(((OntologyResource)obj).resource);
            }
            return false;
        }

        public boolean isAxiom () { return "Axiom".equalsIgnoreCase(type); }
        public boolean isClass () { return "Class".equalsIgnoreCase(type); }
        public boolean isOntology () {
            return "Ontology".equalsIgnoreCase(type);
        }

        public String toString () {
            StringBuilder sb = new StringBuilder ();
            sb.append("> ");
            if (isClass()) sb.append(resource.getLocalName()+" "+uri);
            else if (isAxiom()) sb.append(resource.getId());
            else sb.append(resource.toString());
            sb.append("\n");
            toString (sb, props);
            toString (sb, links);
            return sb.toString();
        }

        void toString (StringBuilder sb, Map<String, Object> values) {
            for (Map.Entry<String, Object> me : values.entrySet()) {
                sb.append("-- "+me.getKey()+":");
                Object value = me.getValue();
                if (value.getClass().isArray()) {
                    for (int i = 0; i < Array.getLength(value); ++i) {
                        sb.append(" ["+Array.get(value, i)+"]");
                    }
                }
                else {
                    sb.append(" ["+value+"]");
                }
                sb.append("\n");
            }
        }
    }

    Map<Resource, OntologyResource> resources = new HashMap<>();
    List<OntologyResource> others = new ArrayList<>();
    OntologyResource ontology;
    
    public OntEntityFactory(GraphDb graphDb) throws IOException {
        super (graphDb);
    }

    public OntEntityFactory (String dir) throws IOException {
        super (dir);
    }

    public OntEntityFactory (File dir) throws IOException {
        super (dir);
    }

    static boolean isDeferred (String field) {
        return RELATIONS.contains(field);
    }
    
    @Override
    protected void init () {
        super.init();
        setIdField (Props.URI);
        setNameField ("label");
        add (N_Name, "label")
            .add(N_Name, "altLabel")
            .add(N_Name, "hasExactSynonym")
            .add(N_Name, "hasRelatedSynonym")
            .add(I_CODE, "hasDbXref")
            .add(I_CODE, "id")
            .add(I_CODE, "notation")
            .add(I_CODE, "hasAlternativeId")
            .add(I_CODE, "SY")
            .add(I_CODE, "RQ")
            .add(I_CODE, "cui")
            .add(H_InChIKey, "inchikey")
            .add(T_Keyword, "inSubset")
            .add(T_Keyword, "type")
            .add(T_Keyword, "hasOBONamespace")
            .add(T_Keyword, "hasSTY")
            ;
    }

    protected void reset () {
        ontology = null;
        resources.clear();
        others.clear();
    }
    
    static String getResourceValue (Resource r) {
        String v = r.getLocalName();
        return v != null ? v : r.getURI();
    }

    protected String transform (String value) {
        if (value.startsWith("UMLS_CUI")) {
            value = value.replaceAll("_CUI", "");
        }
        return value;
    }

    protected static Object map (Object value, Function<Object, Object> f) {
        if (value == null) {
        }
        else if (value.getClass().isArray()) {
            int len = Array.getLength(value);
            for (int i = 0; i < len; ++i) {
                Array.set(value, i, map (Array.get(value, i), f));
            }
        }
        else {
            value = f.apply(value);
        }
        return value;
    }

    /*
     * TODO: NEED TO PUT ALL THESE RULES IN A CONFIG FILE!
     */
    protected Map<String, Object> sigh (Map<String, Object> data) {
        // don't use ICD code for stitching
        List<String> xrefs = new ArrayList<>();
        List<String> icds = new ArrayList<>();
        List<String> others = new ArrayList<>();
        List<String> useful = new ArrayList<>();
        
        Object value = data.remove("hasDbXref");
        if (value != null) {
            if (value.getClass().isArray()) {
                int len = Array.getLength(value);
                for (int i = 0; i < len; ++i) {
                    Object v = Array.get(value, i);
                    if (v instanceof Resource) {
                        others.add(((Resource)v).getURI());
                    }
                    else {
                        String s = v.toString();
                        if ("".equals(s));
                        else if (s.startsWith("ICD"))
                            icds.add(s);
                        else
                            xrefs.add(transform (s));
                    }
                }
            }
            else if (value instanceof Resource) {
                others.add(((Resource)value).getURI());
            }
            else {
                String v = value.toString();
                if ("".equals(v));
                else if (v.startsWith("ICD")) icds.add(v);
                else xrefs.add(transform (v));
            }
        }

        Object obj = data.remove("prefLabel");
        if (obj != null)
            data.put("label", obj);

        for (String p : new String[]{"mapped_to", "related_to", "cui",
                                     "SY", "RQ"}) {
            obj = data.remove(p);
            if (obj != null) {
                data.put(p, map (obj, a -> "UMLS:"+a));
            }
        }
        
        if (ontology == null) {
        }
        else if ("BrendaTissueOBO".equals
                 (ontology.props.get("default-namespace"))) {
            for (String x : xrefs) {
                if (x.startsWith("http://purl.obolibrary.org/obo/BTO"))
                    useful.add(x);
                else
                    others.add(x);
            }
        }
        else if ("gene_ontology".equals
                 (ontology.props.get("default-namespace"))) {
            for (String x : xrefs) {
                String u = x.toUpperCase();
                if (u.startsWith("GOC:")
                    || u.startsWith("PMID")
                    || u.startsWith("ISBN")
                    || u.startsWith("HTTP")
                    || u.startsWith("GO_REF")
                    || (u.startsWith("GO:")
                        && !Character.isDigit(u.charAt(3)))
                    )
                    others.add(x);
                else
                    useful.add(x);
            }
        }
        else if ("chebi_ontology".equals
                 (ontology.props.get("default-namespace"))) {
            for (String x : xrefs) {
                String u = x.toUpperCase();
                if (u.equals("KEGG_COMPOUND")
                    || u.equals("DRUGCENTRAL")
                    || u.equals("IUPAC")
                    || u.equals("CHEMIDPLUS")
                    || u.equals("KEGG_DRUG")
                    || u.equals("CHEBI")
                    || u.equals("WHO_MEDNET")
                    || u.equals("CHEMBL")
                    || u.equals("PDBECHEM")
                    || u.equals("NIST_CHEMISTRY_WEBBOOK")
                    || u.equals("DRUGBANK")
                    || u.equals("UNIPROT")
                    || u.equals("LIPID_MAPS")
                    || u.equals("METACYC")
                    || u.equals("HMDB")
                    || u.equals("SUBMITTER")
                    || u.equals("JCBN")
                    || u.equals("LECITHIN")
                    || u.equals("PHOSPHATIDYLCHOLINE")
                    || u.equals("SPHINGOMYELIN")
                    || u.equals("KEGG_GLYCAN")
                    || u.equals("MOLBASE")
                    || u.equals("ALAN_WOOD'S_PESTICIDES")
                    || u.equals("UM-BBD")
                    || u.equals("CBN")
                    || u.equals("SMID")
                    || u.equals("IUBMB")
                    || u.equals("KNAPSACK")
                    || u.equals("PATENT")
                    || u.equals("IUPHAR")
                    || u.equals("RESID")
                    || u.equals("COME")
                    || u.equals("PDB")
                    || u.equals("LINCS")
                    || u.equals("EMBL")
                    ) {
                    others.add(x);
                    Object old = data.get("inSubset");
                    data.put("inSubset",
                             old != null ? Util.merge(old, x) : x);
                }
                else if (u.startsWith("WIKIPEDIA:")
                         || u.startsWith("METACYC:")) {
                    int pos = x.indexOf(':');
                    String n = x.substring(pos+1).replaceAll("_", " ");
                    if (n.equalsIgnoreCase("LECITHIN")
                        || n.equalsIgnoreCase("PHOSPHATIDYLCHOLINE")
                        || n.equalsIgnoreCase("SPHINGOMYELIN")
                        || n.equalsIgnoreCase("Phosphatidylinositols")
                        || n.equalsIgnoreCase("TRIACYLGLYCEROLS")
                        ) {
                        Object old = data.get("inSubset");
                        data.put("inSubset",
                                 old != null ? Util.merge(old, n) :n);
                    }
                    else {
                        Object old = data.get("hasExactSynonym");
                        data.put("hasExactSynonym",
                                 old != null ? Util.merge(old, n) : n);
                        old = data.get("inSubset");
                        data.put("inSubset",
                                 old != null
                                 ? Util.merge(old, x.substring(0, pos))
                                 : x.substring(0, pos));
                    }
                }
                else if (u.startsWith("PMID:")) {
                    others.add(x);
                }
                else {
                    useful.add(x);
                }
            }
                
            Object label = data.get("label");
            if (label != null) {
                obj = Util.delta(label, new String[]{
                        "Europe PMC", "ChemIDplus", "Reaxys",
                        "KEGG COMPOUND", "DrugCentral", "Beilstein",
                        "KEGG DRUG", "ChEMBL", "Gmelin",
                        "NIST Chemistry WebBook", "LIPID MAPS",
                        "ChEMBL", "ChEBI", "HMDB", "KNApSAcK",
                        "SUBMITTER", "MetaCyc", "DrugBank", "UM-BBD",
                        "Alan Wood's Pesticides", "Diglyceride",
                        "diglycerides", "Diacylglycerol", "SMID",
                        "Triacylglycerol", "triglyceride", "diglyceride",
                        "triglyceride", "Diglyceride"
                    });
                if (obj != Util.NO_CHANGE)
                    data.put("label", obj);
            }
                
            Object syn = data.get("hasRelatedSynonym");
            if (syn != null) {
                obj = Util.delta(syn, new String[]{
                        "Lecithin", "Triacylglycerol", "Diacylglycerol"
                    });
                if (obj != Util.NO_CHANGE)
                    data.put("hasRelatedSynonym", obj);
            }
        }
        else if ("uberon".equals
                 (ontology.props.get("default-namespace"))) {
            for (String x : xrefs) {
                String u = x.toUpperCase();
                if (u.equals("FMA:TA")
                    || u.equals("ZFIN:CURATOR")
                    || u.equals("ZFA:CURATOR")
                    || u.equals("TAO:WD")
                    || u.equals("MP:MP")
                    || u.equals("CL:TM")
                    || u.equals("MA:TH")
                    || u.equals("NIFSTD:NEURONAMES_ABBREVSOURCE")
                    || u.startsWith("NCBITAXON")
                    || u.startsWith("ISBN")
                    || u.startsWith("FEED")
                    || u.startsWith("GOC:")
                    || u.startsWith("FBC:")
                    || (u.startsWith("AAO:")
                        && !Character.isDigit(u.charAt(4)))
                    || (u.startsWith("UBERON:")
                        && !Character.isDigit(u.charAt(7)))
                    || u.startsWith("PHENOSCAPE")
                    || u.startsWith("MGI:")
                    || u.startsWith("BGEE")
                    || u.startsWith("XB:")
                    || u.startsWith("OBOL:")
                    || u.startsWith("HTTP")
                    || u.startsWith("DORLANDS")
                    )
                    others.add(x);
                else
                    useful.add(x);
            }
        }
        else if ("disease_ontology".equals
                 (ontology.props.get("default-namespace"))) {
            for (String x : xrefs) {
                String u = x.toUpperCase();
                if (u.equals("MTH:NOCODE")
                    || u.equals("LS:IEDB")
                    || u.equals("SN:IEDB")
                    || u.startsWith("URL")
                    || u.startsWith("DO:")
                    || u.startsWith("MTHICD9")
                    || u.startsWith("JA:")
                    || u.startsWith("HTTP")
                    )
                    others.add(x);
                else
                    useful.add(x);
            }
        }
        else if ("human_phenotype".equals
                 (ontology.props.get("default-namespace"))) {
            // for hpo, hasDbXref in axiom corresponds to curator or 
            // publication. don't stitch on these
            for (String x : xrefs) {
                String u = x.toUpperCase();
                // sigh.. non-informative xrefs; why are these xrefs?
                if (u.startsWith("UMLS")
                    || (u.startsWith("HP:")
                        && !u.equals("HP:PROBINSON"))
                    || u.startsWith("SNOMEDCT")
                    ) {
                    useful.add(x);
                }
                else if (u.startsWith("MSH")) {
                    useful.add(x.replaceAll("MSH", "MESH"));
                }
                else
                    others.add(x);
            }
        }
        else if (ontology.props.get("title") != null
                 && ((String)ontology.props.get("title"))
                 .startsWith("MONDO")) {
            for (String x : xrefs) {
                String u = x.toUpperCase();
                // these are not stitch identifiers
                if (u.startsWith("MONDO")
                    || u.equals("NCIT:P378")
                    || u.equals("MTH:NOCODE")
                    || u.startsWith("DOI:")
                    || u.startsWith("URL")
                    || u.startsWith("HTTP")
                    || u.startsWith("PMID")
                    || u.startsWith("WIKIPEDIA")
                    ) {
                    others.add(x);
                }
                else
                    useful.add(x);
            }
        }
        else if ("MEDLINEPLUS".equals(ontology.props.get("label"))) {
        }
        else if ("OMIM".equals(ontology.props.get("label"))) {
        }
        else if ("MSH".equals(ontology.props.get("label"))) {
        }
        
        if (!useful.isEmpty() || !others.isEmpty()) {
            xrefs = useful;
            if (!others.isEmpty())
                data.put("_hasDbXref", others.toArray(new String[0]));
        }
        
        if (!xrefs.isEmpty())
            data.put("hasDbXref", xrefs.toArray(new String[0]));
            
        if (!icds.isEmpty())
            data.put("ICD", icds.toArray(new String[0]));

        //logger.info("... registering: "+data);
        return data;
    } // sigh
    
    protected Entity _registerIfAbsent (OntologyResource or) {
        Map<String, Object> data = new TreeMap<>();
        data.put(Props.URI, or.uri);
        data.putAll(or.props);

        for (Map.Entry<String, Object> me : or.links.entrySet()) {
            if (isDeferred (me.getKey()))
                continue;
            
            Object value = me.getValue();
            List<Resource> links = new ArrayList<>();
            if (value.getClass().isArray()) {
                int len = Array.getLength(value);
                Object vs = null;
                for (int i = 0; i < len; ++i) {
                    Resource r = (Resource) Array.get(value, i);
                    vs = Util.merge(vs, getResourceValue (r));
                    links.add(r);
                }
                data.put(me.getKey(), vs);
            }
            else {
                Resource r = (Resource) value;
                data.put(me.getKey(), getResourceValue (r));
                links.add(r);
            }

            for (Resource r : links) {
                String rv = r.getLocalName();
                Object old = data.get(me.getKey());
                data.put(me.getKey(), old != null ? Util.merge(old, rv) : rv);
            }
        }

        // now process all axioms that aren't deferred
        for (OntologyResource ax : or.axioms) {
            Resource r = (Resource) ax.links.get("annotatedProperty");
            String rn = r.getLocalName();
            if (!isDeferred (rn)) {
                Resource t = (Resource) ax.links.get("annotatedTarget");
                Object old = data.get(rn);
                if (t != null) {
                    data.put(rn, old != null
                             ? Util.merge(old, t.getLocalName())
                             : t.getLocalName());
                }
                else {
                    Object v = ax.props.get("annotatedTarget");
                    data.put(rn, old != null ? Util.merge(old, v) : v);
                }
            }

            // copy non-annotated properties
            for (Map.Entry<String, Object> me : ax.props.entrySet()) {
                if (!me.getKey().startsWith("annotated")) {
                    Object old = data.get(me.getKey());
                    data.put(me.getKey(), old != null
                             ? Util.merge(old, me.getValue()) : me.getValue());
                }
            }
        }

        return register (sigh (data));
    }

    boolean _stitch (Entity ent, String name, Resource res) {
        boolean stitched = false;
        String uri = res.getURI();
        if (uri != null) {
            Iterator<Entity> iter = find (Props.URI, uri);
            if (iter.hasNext()) {
                Entity e = iter.next();
                switch (name) {
                case "subClassOf":
                    stitched = ent._stitch(e, R_subClassOf, uri);
                    break;
                case "equivalentClass":
                    stitched = ent._stitch(e, R_equivalentClass, uri);
                    break;
                case "exactMatch":
                    stitched = ent._stitch(e, R_exactMatch, uri);
                    break;
                case "closeMatch":
                    stitched = ent._stitch(e, R_closeMatch, uri);
                    break;
                default:
                    { Map<String, Object> attr = new HashMap<>();
                        attr.put(Props.NAME, name);
                        stitched = ent._stitch(e, R_rel, uri, attr);
                    }
                    //logger.warning("Unknown stitch relationship: "+name);
                }
            }
        }
        return stitched;
    }

    protected void _resolve (OntologyResource or) {
        List<Entity> entities = new ArrayList<>();
        for (Iterator<Entity> iter = find (Props.URI, or.uri);
             iter.hasNext();) {
            Entity e = iter.next();
            entities.add(e);
        }

        if (!entities.isEmpty()) {
            for (Map.Entry<String, Object> me : or.links.entrySet()) {
                if (RELATIONS.contains(me.getKey())) {
                    Object value = me.getValue();
                    if (value.getClass().isArray()) {
                        int len = Array.getLength(value);
                        for (int i = 0; i < len; ++i) {
                            Resource res = (Resource) Array.get(value, i);
                            for (Entity e : entities)
                                _stitch (e, me.getKey(), res);
                        }
                    }
                    else {
                        Resource res = (Resource) value;
                        for (Entity e : entities)
                            _stitch (e, me.getKey(), res);
                    }
                }
                /*
                else if (getStitchKey (me.getKey()) == null) {
                    logger.warning("Unknown link resource: "+me.getKey());
                }
                */
            }
        }
        else {
            logger.warning("Unable to resolve "+or.uri);
        }
    }

    protected Entity registerIfAbsent (OntologyResource or) {
        Entity ent = null;
        try (Transaction tx = gdb.beginTx()) {
            ent = _registerIfAbsent (or);
            tx.success();
        }
        catch (Exception ex) {
            logger.log(Level.SEVERE, "Can't register resource: "+or, ex);
            throw new RuntimeException (ex);
        }
        return ent;
    }

    protected void resolve (OntologyResource or) {
        try (Transaction tx = gdb.beginTx()) {
            _resolve (or);
            tx.success();
        }
    }

    public DataSource register (String file) throws Exception {
        DataSource ds = super.register(new File (file));

        Model model = ModelFactory.createDefaultModel();
        model.read(file);

        reset ();        
        Set<OntologyResource> axioms = new HashSet<>();
        
        logger.info("Loading resources...");
        for (ResIterator iter = model.listSubjects(); iter.hasNext();) {
            Resource res = iter.next();
            
            OntologyResource or = new OntologyResource (res);
            if (or.isAxiom()) {
                res = (Resource) or.links.get("annotatedSource");
                OntologyResource ref = resources.get(res);
                if (ref != null)
                    ref.axioms.add(or);
                else
                    axioms.add(or);
            }
            else if (or.isClass()) {
                if (or.uri != null)
                    resources.put(res, or);
                else
                    logger.warning("Ignore class "+res);
            }
            else if (or.isOntology()) {
                ontology = or;
                for (Map.Entry<String, Object> me : or.props.entrySet())
                    if (!"".equals(me.getKey()))
                        ds.set(me.getKey(), me.getValue());
                
                for (Map.Entry<String, Object> me : or.links.entrySet()) {
                    Object value = me.getValue();
                    if (value.getClass().isArray()) {
                        int len = Array.getLength(value);
                        String[] vals = new String[len];
                        for (int i = 0; i < len; ++i) {
                            Resource r = (Resource) Array.get(value, i);
                            vals[i] = r.getURI();
                        }
                        ds.set(me.getKey(), vals);
                    }
                    else {
                        Resource r = (Resource) me.getValue();
                        if (res.getURI() != null)
                            ds.set(me.getKey(), r.getURI());
                    }
                }
                logger.info(">>>>>>> Ontology <<<<<<<<\n"+or);
            }
            else {
                logger.warning("Resource type "
                               +or.type+" not recognized:\n"+or);
                others.add(or);
            }
        }

        List<OntologyResource> unresolved = new ArrayList<>();
        for (OntologyResource or : axioms) {
            Resource res = (Resource) or.links.get("annotatedSource");
            OntologyResource ref = resources.get(res);
            if (ref != null) {
                ref.axioms.add(or);
            }
            else {
                //logger.warning("Unable to resolve axiom:\n"+or);
                unresolved.add(or);
            }
        }

        // register entities
        for (OntologyResource or : resources.values()) {
            System.out.print(or);            
            System.out.println("..."+or.axioms.size()+" axiom(s)");
            for (OntologyResource ax : or.axioms) {
                System.out.print("   "+ax);
            }
            Entity ent = registerIfAbsent (or);
            System.out.println("+++++++ "+ent.getId()+" +++++++");

            /*if (ent.getId() > 2000l)
              break;*/
        }

        // resolve entities
        for (OntologyResource or : resources.values()) {
            resolve (or);
        }

        // resolve other references (if any)
        for (OntologyResource or : others) {
            if (or.uri != null)
                resolve (or);
        }
        
        logger.info(resources.size()+" resource classes!");
        if (!unresolved.isEmpty()) {
            logger.warning("!!!!! "+unresolved.size()
                           +" unresolved axioms !!!!!");
            for (OntologyResource or : unresolved)
                System.out.println(or);
        }

        return ds;
    }

    static void iterateModel (Model model) {
        Map<String, Integer> classes = new TreeMap<>();
        for (ResIterator iter = model.listSubjects(); iter.hasNext();) {
            Resource res = iter.next();
            String name = res.getLocalName();
            if (name != null && name.startsWith("MONDO")) {
                Integer c = classes.get(name);
                classes.put(name,c==null?1:c+1);
            }
            
            System.out.println(name+" "+res);
            for (StmtIterator it = res.listProperties(); it.hasNext(); ) {
                Statement stm = it.next();
                Property prop = stm.getPredicate();
                RDFNode obj = stm.getObject();
                System.out.print("-- "+prop.getLocalName()+": ");
                if (obj.isResource()) {
                    System.out.print(obj.asResource().getURI());
                }
                else if (obj.isLiteral()) {
                    System.out.print(obj.asLiteral().getValue());
                }
                System.out.println();
            }
            System.out.println();
        }
        System.out.println("OWL classes: "+classes.size());
    }

    static void iterateModel (OntModel model) {
        model.setDynamicImports(false);
        model.loadImports();
        
        System.out.println("Import Ontologies:");
        for (String uri : model.listImportedOntologyURIs()) {
            System.out.println("-- "+uri);
            model.addLoadedImport(uri);
        }

        System.out.println(model.countSubModels()+" sub models!");
        
        System.out.println("Annotation Properties:");
        for (ExtendedIterator<AnnotationProperty> iter =
                 model.listAnnotationProperties(); iter.hasNext(); ) {
            AnnotationProperty prop = iter.next();
            System.out.println("-- "+prop.getLocalName());
        }

        System.out.println("Classes:");
        int nc = 0;
        for (ExtendedIterator<OntClass> iter =
                 //model.listHierarchyRootClasses();
                 model.listNamedClasses();
             iter.hasNext(); ) {
            OntClass ont = iter.next();
            System.out.println("-- "+ont.getLabel(null)+" "+ont);
            ++nc;
        }
        System.out.println(nc+" classes!");
    }
    
    public static void main(String[] argv) throws Exception {
        if (argv.length < 2) {
            logger.info("Usage: "+OntEntityFactory.class.getName()
                        +" DBDIR [OWL|TTL]...");
            System.exit(1);
        }

        try (OntEntityFactory def = new OntEntityFactory (argv[0])) {
            for (int i = 1; i < argv.length; ++i) 
                def.register(argv[i]);
        }
        
        /*
        // why is processing ontology model so slow?
        //OntModel model = ModelFactory.createOntologyModel();
        Model model = ModelFactory.createDefaultModel();
        // read the RDF/XML file
        model.read(argv[0]);

        //Model model = RDFDataMgr.loadModel(argv[0]);
        logger.info(argv[0]+": "+model.getClass());
        iterateModel (model);
        
        // write it to standard out
        //model.write(System.out);         
        */
    }
}
