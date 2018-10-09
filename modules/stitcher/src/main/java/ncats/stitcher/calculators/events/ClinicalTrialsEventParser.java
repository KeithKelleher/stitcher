package ncats.stitcher.calculators.events;

import ncats.stitcher.calculators.EventCalculator;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

public class DrugsAtFDAEventParser extends EventParser {
    public DrugsAtFDAEventParser() {
        super ("approvalYears.txt");
    }

    public List<Event> getEvents(Map<String, Object> payload) {
        List<Event> events = new ArrayList<>();
        Event event = null;
        Object id = payload.get("UNII");
        Object content = payload.get("Date");
        if (content != null) {
            try {
                Date date = EventCalculator.SDF2.parse((String)content);
                event = new Event(name, id, Event.EventKind.ApprovalRx);
                event.jurisdiction = "US";
                event.startDate = date;
                //event.endDate;
                event.active = (String) payload.get("active");
                event.source = (String) payload.get("Date_Method");
                event.URL = (String) payload.get("Url");
                event.approvalAppId = (String) payload.get("App_Type") +
                        (String) payload.get("App_No");
                event.product = (String) payload.get("Product");
                event.sponsor = (String) payload.get("Sponsor");
                //event.route;
                //event.withDrawnYear;
                //event.marketingStatus;
                //event.productCategory;
                event.comment = (String) payload.get("Comment");
            }
            catch (Exception ex) {
                EventCalculator.logger.log(Level.SEVERE,
                           "Can't parse startDate: \""+content+"\"", ex);
            }
        }

        if (event != null) events.add(event);
        return events;
    }
}
