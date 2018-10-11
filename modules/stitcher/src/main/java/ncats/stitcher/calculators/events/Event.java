package ncats.stitcher.calculators.events;

import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.Field;
import java.util.Date;

public class Event implements Cloneable{
    public EventKind kind;
    public String source;
    public Object id;
    public Date startDate;
    public Date endDate;
    public String active;
    public String jurisdiction;
    public String comment; // reference

    public String route;

    public String approvalAppId;

    public String product;
    public String sponsor;
    public String URL;

    @Deprecated public String marketingStatus;
    @Deprecated public String productCategory;

    //@Deprecated public Integer withDrawnYear;

    /**
     * Create a deep clone.
     * @return returns an event.
     */
    @Override
    public Event clone()  {
        Event e = null;
        try {
            e = (Event) super.clone();
        } catch (CloneNotSupportedException e1) {
            throw new RuntimeException(e1); //shouldn't happen
        }
        //startDate is mutable so make defensive copy
        if(startDate != null) {
            e.startDate = new Date(startDate.getTime());
        }
        if(endDate != null) {
            e.endDate = new Date(endDate.getTime());
        }
        return e;
    }

    public enum EventKind {
        Publication,
        Filing,
        Designation,
        Clinical,
        ApprovalRx {
            @Override
            public boolean isApproved() {
                return true;
            }
        },
        Marketed {
            @Override
            public boolean wasMarketed(){
                return true;
            }
        },
        ApprovalOTC {
            @Override
            public boolean isApproved(){
                return true;
            }
        },
        Withdrawn {
            @Override
            public boolean wasMarketed(){
                return true;
            }
        },
        Discontinued {
            @Override
            public boolean wasMarketed(){
                return true;
            }
        },
        Other
        ;

        public boolean isApproved(){
            return false;
        }

        public boolean wasMarketed(){
            return isApproved();
        }
    }

    public Event(String source, Object id, EventKind kind, Date startDate) {
        this.source = source != null ? source : "*";
        this.id = id != null ? id : "*";
        this.kind = kind;
        this.startDate = startDate;
    }

    public Event(String source, Object id, EventKind kind) {
        this(source, id, kind, null);
    }

    public Event(JsonNode node) {
        for (Field field: this.getClass().getDeclaredFields()) {
            if (node.has(field.getName())) {
                try {
                    if (field.getType() == String.class || field.getType() == Object.class)
                        field.set(this, node.get(field.getName()).textValue());
                    else if (field.getType() == Date.class) {
                        String str = node.get(field.getName()).textValue();
                        int year = Integer.valueOf(str.substring(0, 4));
                        int month = Integer.valueOf(str.substring(str.indexOf("-")+1,str.lastIndexOf("-")));
                        int day = Integer.valueOf(str.substring(str.lastIndexOf("-")+1));
                        Date date = new Date(year-1900, month-1, day);
                        field.set(this, date);
                    }
                    else if (field.getType() == EventKind.class) {
                        String str = node.get(field.getName()).textValue();
                        EventKind kind = EventKind.valueOf(str);
                        field.set(this, kind);
                    }
                    else throw new IllegalArgumentException();
                } catch (Exception e) {
                    System.err.println("Could not set:" + field.getName() + " to value " + node.get(field.getName()));
                }
            }
        }
    }
}
