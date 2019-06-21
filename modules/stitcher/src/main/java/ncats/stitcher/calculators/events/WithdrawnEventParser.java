package ncats.stitcher.calculators.events;

import ncats.stitcher.calculators.EventCalculator;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;

public class WithdrawnEventParser extends EventParser{

    private static class WithdrawnInfo{
        private final List<String> countriesWithdrawn;
        private final String reason;
        //TODO add other fields like startDate withdrawn


        public WithdrawnInfo(String reason, List<String> countriesWithdrawn) {
            this.countriesWithdrawn = countriesWithdrawn;
            this.reason = reason;
        }

        public List<String> getCountriesWithdrawn() {
            return countriesWithdrawn;
        }

        public String getReason() {
            return reason;
        }
    }
    private static class WithdrawnStatusLookup{
        private final Map<String, WithdrawnInfo> withdrawnsById = new HashMap<>();

        private static Pattern COL_PATTERN = Pattern.compile("\t");
        private static Pattern COUNTRY_PATTERN = Pattern.compile("\\|");

        WithdrawnStatusLookup(InputStream in) throws IOException{
            try(BufferedReader reader = new BufferedReader(new InputStreamReader(in))){
                String[] headerArray = COL_PATTERN.split(reader.readLine());
                Map<String, Integer> header = new HashMap<>();
                for( int i=0; i< headerArray.length; i++){
                    header.put(headerArray[i], i);
                }
                //status	generic_name	brand_name	synonym	form	class	moa	indication	description	year_launched	date_launched	country_launched	date_shortage	year_shortage	URL	year_adr_report	year_withdrawn	date_withdrawn	country_withdrawn	reason_withdrawn	year_remarketed	source	unii	smiles

                int uniiOffset = header.get("unii");
                int reasonWithDrawn = header.get("reason_withdrawn");
                int status = header.get("status");
                int countryWithdrawn = header.get("country_withdrawn");


                Map<String, List<String>> cache = new HashMap<>();
                String line;
                while( (line=reader.readLine()) !=null){
                    String[] cols = COL_PATTERN.split(line);

                    if("Withdrawn".equals(cols[status])){
                        String id = cols[uniiOffset];
                        withdrawnsById.put(id, new WithdrawnInfo(
                                cols[reasonWithDrawn],
                                cache.computeIfAbsent(cols[countryWithdrawn], k-> Arrays.asList(COUNTRY_PATTERN.split(k)))));


                    }
                }
            }
        }

        public WithdrawnInfo getWithdrawnInfo(String unii){
            return withdrawnsById.get(unii);
        }
    }
    static WithdrawnStatusLookup withdrawnStatusLookup;
    static{

        try{
            withdrawnStatusLookup = new WithdrawnStatusLookup(new BufferedInputStream(new FileInputStream("data/combined_withdrawn_shortage_drugs.txt")));
        }catch(IOException e) {

            throw new UncheckedIOException(e);
        }
    }

    public WithdrawnEventParser() {
        super("Withdrawn and Shortage Drugs List Feb 2018");
    }

    @Override
    public void produceEvents(Map<String, Object> payload) {

        String unii = (String) payload.get("unii");
//        WithdrawnInfo info = withdrawnStatusLookup.getWithdrawnInfo(unii);
//        if(info !=null){
        try {
            Event e = new Event(name, unii, Event.EventKind.Withdrawn);
            //e.comment = info.getReason();

            e.source = name;
            //e.jurisdiction;
            if (payload.containsKey("date_launched") && !"NA".equals(payload.get("date_launched")))
                e.startDate = EventCalculator.SDF
                        .parse((String)payload.get("date_launched"));
            else if (payload.containsKey("year_launched") && !"NA".equals(payload.get("year_launched")))
                try {
                    e.startDate = EventCalculator.SDF
                            .parse(payload.get("year_launched")+"-12-31");
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            if (payload.containsKey("date_withdrawn") && !"NA".equals(payload.get("date_withdrawn")))
                e.endDate = EventCalculator.SDF
                        .parse((String)payload.get("date_withdrawn"));
            else if (payload.containsKey("year_withdrawn") && !"NA".equals(payload.get("year_withdrawn")))
                try {
                    e.endDate = EventCalculator.SDF
                            .parse(payload.get("year_withdrawn") + "-12-31");
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            //e.active;
            if (payload.containsKey("URL") && !"NA".equals(payload.get("URL")))
                e.URL = (String)payload.get("URL");
            //e.approvalAppId;
            if (payload.containsKey("brand_name") && !"NA".equals(payload.get("brand_name")))
                e.product = (String)payload.get("brand_name");
            else if (payload.containsKey("generic_name") && !"NA".equals(payload.get("generic_name")))
                e.product = (String)payload.get("generic_name");
            //e.sponsor;
            //e.route;
            if (payload.containsKey("source") && !"NA".equals(payload.get("source")))
                e.comment = (String)payload.get("source");

//            if(info.countriesWithdrawn.isEmpty()) {
//                events.put(String.valueOf(System.identityHashCode(e)), e);
//            }else {
//                for (String country : info.getCountriesWithdrawn()) {
//                    Event e2 = e.clone();
//                    e2.jurisdiction = country;
//                    events.put(String.valueOf(System.identityHashCode(e2)), e2);
//                }
//            }

            if (payload.containsKey("country_withdrawn") && !"NA".equals(payload.get("country_withdrawn"))) {
                e.jurisdiction = (String) payload.get("country_withdrawn");
            }
            events.put(String.valueOf(System.identityHashCode(e)), e);

        } catch (Exception ex) {
          ex.printStackTrace();
        }


        return;
    }
}
