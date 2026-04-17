import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.dnd.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;
import java.util.List;
import java.util.stream.*;
import javax.imageio.ImageIO;
import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.*;

// ─── Subject Model ────────────────────────────────────────────────────────────
class Subject implements Serializable {
    private static final long serialVersionUID = 4L;
    int dbId = -1; // SQLite row id
    String name; Date deadline; boolean completed; String priority;
    int pomodorosDone; boolean liked; boolean pinned; String notes; int xp;
    Date completedDate;
    int schedHour = -1;

    Subject(String name, Date deadline, String priority) {
        this.name = name; this.deadline = deadline; this.completed = false;
        this.priority = priority; this.pomodorosDone = 0; this.liked = false;
        this.pinned = false; this.notes = ""; this.xp = 0;
    }
    long getDeadlineTime() { return deadline.getTime(); }
    static int priorityWeight(String p) { return "HIGH".equals(p)?1:"MEDIUM".equals(p)?2:3; }
    long daysLeft() {
        return (long)Math.ceil((deadline.getTime()-System.currentTimeMillis())/(1000.0*60*60*24));
    }
}

// ─── Quick Note ───────────────────────────────────────────────────────────────
class QuickNote implements Serializable {
    private static final long serialVersionUID = 1L;
    int dbId = -1;
    String title, body; Color color; long created;
    QuickNote(String t,String b,Color c){title=t;body=b;color=c;created=System.currentTimeMillis();}
}

// ─── Theme ────────────────────────────────────────────────────────────────────
class Theme {
    String name; Color bg,panel,card,fg,sub,accent,accent2;
    Theme(String n,Color bg,Color panel,Color card,Color fg,Color sub,Color acc,Color acc2){
        name=n;this.bg=bg;this.panel=panel;this.card=card;this.fg=fg;this.sub=sub;accent=acc;accent2=acc2;
    }
}

// ─── Background Panel ─────────────────────────────────────────────────────────
class BackgroundPanel extends JPanel {
    private BufferedImage bgImg;
    BackgroundPanel(LayoutManager lm){super(lm);setOpaque(true);}
    void setBackground(BufferedImage img){this.bgImg=img;repaint();}
    boolean hasBg(){return bgImg!=null;}
    @Override protected void paintComponent(Graphics g){
        if(bgImg!=null){
            Graphics2D g2=(Graphics2D)g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(bgImg,0,0,getWidth(),getHeight(),null);
            g2.setColor(new Color(0,0,0,90)); g2.fillRect(0,0,getWidth(),getHeight());
            g2.dispose();
        } else { super.paintComponent(g); }
    }
}

// ─── Glass Panel ─────────────────────────────────────────────────────────────
class GlassPanel extends JPanel {
    Color glassColor;
    GlassPanel(LayoutManager lm,Color c){super(lm);glassColor=c;setOpaque(false);}
    GlassPanel(Color c){this(new FlowLayout(),c);}
    @Override protected void paintComponent(Graphics g){
        Graphics2D g2=(Graphics2D)g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(glassColor); g2.fillRoundRect(0,0,getWidth()-1,getHeight()-1,12,12);
        g2.setColor(new Color(255,255,255,20));
        g2.drawRoundRect(0,0,getWidth()-1,getHeight()-1,12,12);
        g2.dispose(); super.paintComponent(g);
    }
}

// ─── Table Renderer ───────────────────────────────────────────────────────────
class SubjectTableRenderer extends DefaultTableCellRenderer {
    @Override public Component getTableCellRendererComponent(JTable t,Object v,boolean sel,boolean f,int row,int col){
        Component c=super.getTableCellRendererComponent(t,v,sel,f,row,col);
        int mr=t.convertRowIndexToModel(row);
        String status=(String)t.getModel().getValueAt(mr,3),priority=(String)t.getModel().getValueAt(mr,2);
        long days=0; try{days=Long.parseLong((String)t.getModel().getValueAt(mr,4));}catch(Exception e){}
        if(!sel){
            if("Done".equals(status)){c.setBackground(new Color(39,174,96,60));c.setForeground(new Color(39,174,96));}
            else if(days<0){c.setBackground(new Color(231,76,60,60));c.setForeground(new Color(231,76,60));}
            else if(days<=1){c.setBackground(new Color(230,126,34,60));c.setForeground(new Color(180,80,10));}
            else if("HIGH".equals(priority)){c.setBackground(new Color(155,89,182,40));c.setForeground(new Color(130,60,180));}
            else{c.setBackground(StudyPlannerApp.thm.card);c.setForeground(StudyPlannerApp.thm.fg);}
        }
        setBorder(new EmptyBorder(4,8,4,8)); return c;
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// WEEK CALENDAR PANEL
// ═══════════════════════════════════════════════════════════════════════════════
class WeekCalendarPanel extends JPanel implements Scrollable {
    static final int TIME_W=72,COL_W=128,HDR_H=68,ALLDAY_H=38,HR_H=64,START_HR=7,END_HR=22;
    Calendar weekStart=Calendar.getInstance();
    static final Color[] EV_COLORS={
        new Color(99,102,241),new Color(52,211,153),new Color(251,113,52),
        new Color(244,114,182),new Color(56,189,248),new Color(139,92,246),
        new Color(250,204,21),new Color(34,197,94),new Color(248,113,113),new Color(20,184,166)
    };
    WeekCalendarPanel(){
        goToday_init();
        int totalH=HDR_H+ALLDAY_H+(END_HR-START_HR)*HR_H+20;
        int totalW=TIME_W+7*COL_W+2;
        setPreferredSize(new Dimension(totalW,totalH));
        setOpaque(false);
        addMouseListener(new MouseAdapter(){@Override public void mouseClicked(MouseEvent e){handleClick(e);}});
    }
    void goToday_init(){
        weekStart=Calendar.getInstance();
        weekStart.set(Calendar.DAY_OF_WEEK,Calendar.SUNDAY);
        weekStart.set(Calendar.HOUR_OF_DAY,0);weekStart.set(Calendar.MINUTE,0);
        weekStart.set(Calendar.SECOND,0);weekStart.set(Calendar.MILLISECOND,0);
    }
    void prevWeek(){weekStart.add(Calendar.WEEK_OF_YEAR,-1);repaint();}
    void nextWeek(){weekStart.add(Calendar.WEEK_OF_YEAR,1);repaint();}
    void goToday(){goToday_init();repaint();}
    String headerText(){
        Calendar end=(Calendar)weekStart.clone();end.add(Calendar.DAY_OF_YEAR,6);
        SimpleDateFormat mf=new SimpleDateFormat("MMMM yyyy");
        if(weekStart.get(Calendar.MONTH)==end.get(Calendar.MONTH))return mf.format(weekStart.getTime());
        return new SimpleDateFormat("MMM").format(weekStart.getTime())+" – "+mf.format(end.getTime());
    }
    @Override protected void paintComponent(Graphics g){
        super.paintComponent(g);
        Graphics2D g2=(Graphics2D)g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Theme thm=StudyPlannerApp.thm;
        Calendar today=Calendar.getInstance();
        g2.setColor(new Color(thm.bg.getRed(),thm.bg.getGreen(),thm.bg.getBlue(),230));
        g2.fillRect(0,0,getWidth(),getHeight());
        g2.setColor(StudyPlannerApp.alphaColor(thm.panel,240));
        g2.fillRect(0,0,TIME_W,HDR_H);
        String[] DAYS={"SUN","MON","TUE","WED","THU","FRI","SAT"};
        for(int d=0;d<7;d++){
            Calendar day=(Calendar)weekStart.clone();day.add(Calendar.DAY_OF_YEAR,d);
            int x=TIME_W+d*COL_W;boolean isToday=sameDay(day,today);
            Color headerBg=isToday?StudyPlannerApp.alphaColor(thm.accent,30):
                    (d==0||d==6)?StudyPlannerApp.alphaColor(thm.panel,200):StudyPlannerApp.alphaColor(thm.panel,240);
            g2.setColor(headerBg);g2.fillRect(x,0,COL_W,HDR_H);
            g2.setFont(new Font("SansSerif",Font.PLAIN,11));g2.setColor(isToday?thm.accent:thm.sub);
            FontMetrics fmS=g2.getFontMetrics();String dname=DAYS[d];
            g2.drawString(dname,x+(COL_W-fmS.stringWidth(dname))/2,HDR_H-42);
            String dnum=String.valueOf(day.get(Calendar.DAY_OF_MONTH));
            g2.setFont(new Font("SansSerif",Font.BOLD,24));FontMetrics fmD=g2.getFontMetrics();
            int cx=x+(COL_W-fmD.stringWidth(dnum))/2,cy=HDR_H-18;
            if(isToday){g2.setColor(thm.accent);g2.fillOval(cx-10,cy-26,fmD.stringWidth(dnum)+20,34);g2.setColor(Color.WHITE);}
            else g2.setColor(thm.fg);
            g2.drawString(dnum,cx,cy);
        }
        int allY=HDR_H;
        g2.setColor(StudyPlannerApp.alphaColor(thm.card,200));g2.fillRect(0,allY,getWidth(),ALLDAY_H);
        g2.setFont(new Font("SansSerif",Font.PLAIN,10));g2.setColor(thm.sub);g2.drawString("All-day",6,allY+ALLDAY_H/2+4);
        Map<Integer,Integer> alldayCnt=new HashMap<>();
        List<Subject> sList=StudyPlannerApp.list;
        for(int i=0;i<sList.size();i++){Subject s=sList.get(i);Calendar sc=Calendar.getInstance();sc.setTime(s.deadline);
            for(int d=0;d<7;d++){Calendar day=(Calendar)weekStart.clone();day.add(Calendar.DAY_OF_YEAR,d);
                if(sameDay(sc,day)){int cnt=alldayCnt.getOrDefault(d,0);int x=TIME_W+d*COL_W+2+cnt*62;
                    if(x+60<=TIME_W+(d+1)*COL_W){Color ec=EV_COLORS[i%EV_COLORS.length];
                        if(s.completed)ec=new Color(ec.getRed(),ec.getGreen(),ec.getBlue(),140);
                        g2.setColor(ec);g2.fillRoundRect(x,allY+4,58,ALLDAY_H-8,6,6);g2.setColor(Color.WHITE);
                        g2.setFont(new Font("SansSerif",Font.BOLD,9));
                        g2.drawString(trunc(s.completed?"✓ "+s.name:s.name,g2.getFontMetrics(),50),x+4,allY+ALLDAY_H/2+4);
                        alldayCnt.put(d,cnt+1);}}}}
        for(int d=0;d<7;d++){Calendar day=(Calendar)weekStart.clone();day.add(Calendar.DAY_OF_YEAR,d);
            String key=new SimpleDateFormat("yyyy-MM-dd").format(day.getTime());
            String mark=StudyPlannerApp.calMarks.get(key);
            if(mark!=null&&!mark.isEmpty()){int cnt=alldayCnt.getOrDefault(d,0);int x=TIME_W+d*COL_W+2+cnt*62;
                if(x+60<=TIME_W+(d+1)*COL_W){Color mc=new Color(251,191,36,220);
                    g2.setColor(mc);g2.fillRoundRect(x,allY+4,58,ALLDAY_H-8,6,6);
                    g2.setColor(new Color(80,60,0));g2.setFont(new Font("SansSerif",Font.BOLD,9));
                    g2.drawString(trunc(mark,g2.getFontMetrics(),50),x+4,allY+ALLDAY_H/2+4);alldayCnt.put(d,cnt+1);}}}
        int gridY=HDR_H+ALLDAY_H;
        for(int d=0;d<7;d++){Calendar day=(Calendar)weekStart.clone();day.add(Calendar.DAY_OF_YEAR,d);
            if(sameDay(day,today)){int x=TIME_W+d*COL_W;
                g2.setColor(new Color(thm.accent.getRed(),thm.accent.getGreen(),thm.accent.getBlue(),8));
                g2.fillRect(x,gridY,COL_W,(END_HR-START_HR)*HR_H);}}
        for(int hr=START_HR;hr<=END_HR;hr++){int y=gridY+(hr-START_HR)*HR_H;
            g2.setColor(new Color(thm.sub.getRed(),thm.sub.getGreen(),thm.sub.getBlue(),45));g2.drawLine(TIME_W,y,getWidth(),y);
            if(hr<END_HR){g2.setColor(new Color(thm.sub.getRed(),thm.sub.getGreen(),thm.sub.getBlue(),20));
                float[] dash={4f,4f};g2.setStroke(new BasicStroke(1,BasicStroke.CAP_BUTT,BasicStroke.JOIN_MITER,10,dash,0));
                g2.drawLine(TIME_W,y+HR_H/2,getWidth(),y+HR_H/2);g2.setStroke(new BasicStroke(1));}
            g2.setColor(StudyPlannerApp.alphaColor(thm.bg,230));g2.fillRect(0,y,TIME_W,HR_H);
            String timeStr;if(hr==12)timeStr="12 PM";else if(hr<12)timeStr=hr+" AM";else timeStr=(hr-12)+" PM";
            g2.setFont(new Font("SansSerif",Font.PLAIN,11));g2.setColor(thm.sub);FontMetrics fm=g2.getFontMetrics();
            g2.drawString(timeStr,TIME_W-fm.stringWidth(timeStr)-6,y+14);}
        for(int d=0;d<7;d++){Calendar day=(Calendar)weekStart.clone();day.add(Calendar.DAY_OF_YEAR,d);
            List<Subject> daySubjects=new ArrayList<>();List<Integer> dayIndices=new ArrayList<>();
            for(int i=0;i<sList.size();i++){Subject s=sList.get(i);Calendar sc=Calendar.getInstance();sc.setTime(s.deadline);
                if(sameDay(sc,day)&&!s.completed){daySubjects.add(s);dayIndices.add(i);}}
            for(int i=0;i<sList.size();i++){Subject s=sList.get(i);Calendar sc=Calendar.getInstance();sc.setTime(s.deadline);
                if(sameDay(sc,day)&&s.completed){daySubjects.add(s);dayIndices.add(i);}}
            int n=daySubjects.size();if(n==0)continue;int baseX=TIME_W+d*COL_W;
            for(int ei=0;ei<n;ei++){Subject s=daySubjects.get(ei);int colorIdx=dayIndices.get(ei);
                int dispHr=s.schedHour>0?s.schedHour:"HIGH".equals(s.priority)?9:"MEDIUM".equals(s.priority)?12:15;
                dispHr=Math.max(START_HR,Math.min(END_HR-1,dispHr));int offsetMin=ei*20;
                int totalMins=(dispHr-START_HR)*60+offsetMin;int ey=gridY+(int)(totalMins*(double)HR_H/60);
                int eH=Math.max(HR_H-6,52);int ew=(n==1)?COL_W-6:Math.max(48,(COL_W-6)/Math.min(n,3));
                int ex=baseX+3+(ei%3)*(ew+1);Color ec=EV_COLORS[colorIdx%EV_COLORS.length];
                if(s.completed)ec=new Color(ec.getRed(),ec.getGreen(),ec.getBlue(),130);
                g2.setColor(new Color(0,0,0,20));g2.fillRoundRect(ex+2,ey+3,ew,eH,8,8);
                GradientPaint gp=new GradientPaint(ex,ey,ec,ex,ey+eH,
                        new Color(Math.max(0,ec.getRed()-30),Math.max(0,ec.getGreen()-30),Math.max(0,ec.getBlue()-30),ec.getAlpha()));
                g2.setPaint(gp);g2.fillRoundRect(ex,ey,ew,eH,8,8);
                g2.setColor(ec.darker());g2.fillRoundRect(ex,ey,4,eH,4,4);
                g2.setColor(Color.WHITE);int ty=ey+16;
                int h12=dispHr>12?dispHr-12:dispHr;String ampm=dispHr>=12?"PM":"AM";
                g2.setFont(new Font("SansSerif",Font.PLAIN,9));
                g2.drawString(String.format("%d:%02d %s",h12,offsetMin%60==0?0:offsetMin%60,ampm),ex+6,ty);ty+=12;
                g2.setFont(new Font("SansSerif",Font.BOLD,11));
                g2.drawString(trunc(s.name,g2.getFontMetrics(),ew-10),ex+6,ty);ty+=13;
                if(eH>48){g2.setFont(new Font("SansSerif",Font.PLAIN,10));g2.drawString("Priority: "+s.priority,ex+6,ty);ty+=12;}
                if(eH>62){g2.setFont(new Font("SansSerif",Font.PLAIN,10));long dl=s.daysLeft();
                    String dls=dl<0?"⚠ Overdue":dl==0?"Due today!":dl+"d left";g2.drawString(dls,ex+6,ty);}
                if(s.completed){g2.setColor(new Color(255,255,255,80));g2.fillRoundRect(ex,ey,ew,eH,8,8);
                    g2.setFont(new Font("SansSerif",Font.BOLD,16));g2.setColor(new Color(255,255,255,200));g2.drawString("✓",ex+ew/2-8,ey+eH/2+6);}}}
        for(int d=0;d<7;d++){Calendar day=(Calendar)weekStart.clone();day.add(Calendar.DAY_OF_YEAR,d);
            if(sameDay(day,today)){int nowH=today.get(Calendar.HOUR_OF_DAY),nowM=today.get(Calendar.MINUTE);
                if(nowH>=START_HR&&nowH<END_HR){int nowY=gridY+(nowH-START_HR)*HR_H+nowM*HR_H/60;
                    g2.setColor(new Color(239,68,68));g2.fillOval(TIME_W-7,nowY-5,12,12);
                    g2.setStroke(new BasicStroke(2));g2.drawLine(TIME_W,nowY,getWidth(),nowY);
                    g2.setStroke(new BasicStroke(1));}break;}}
    }
    void handleClick(MouseEvent e){
        int x=e.getX(),y=e.getY();if(x<TIME_W||x>=TIME_W+7*COL_W)return;
        int d=(x-TIME_W)/COL_W;if(d<0||d>6)return;
        Calendar clickedDay=(Calendar)weekStart.clone();clickedDay.add(Calendar.DAY_OF_YEAR,d);
        String dateKey=new SimpleDateFormat("yyyy-MM-dd").format(clickedDay.getTime());
        if(y>=HDR_H&&y<HDR_H+ALLDAY_H)showMarkDialog(clickedDay,dateKey);
        else if(y>=HDR_H+ALLDAY_H)showMarkDialog(clickedDay,dateKey);
    }
    void showMarkDialog(Calendar day,String dateKey){
        String existing=StudyPlannerApp.calMarks.getOrDefault(dateKey,"");
        String dateStr=new SimpleDateFormat("EEE, dd MMM yyyy").format(day.getTime());
        String[] opts={"📖 Studied","🏖️ Holiday","💪 Exercise","🧘 Rest","🎉 Event","🎓 Exam","✏️ Custom…","🗑 Clear","Cancel"};
        int choice=JOptionPane.showOptionDialog(null,"Mark: "+dateStr+(existing.isEmpty()?"":" · Current: "+existing),
                "📅 Mark Day",JOptionPane.DEFAULT_OPTION,JOptionPane.PLAIN_MESSAGE,null,opts,opts[0]);
        if(choice<0||choice==8)return;
        if(choice==6){String c=JOptionPane.showInputDialog("Note:",existing);if(c!=null)StudyPlannerApp.calMarks.put(dateKey,c.trim());}
        else if(choice==7)StudyPlannerApp.calMarks.remove(dateKey);
        else StudyPlannerApp.calMarks.put(dateKey,opts[choice]);
        StudyPlannerApp.saveData();repaint();
    }
    static boolean sameDay(Calendar a,Calendar b){
        return a.get(Calendar.YEAR)==b.get(Calendar.YEAR)&&a.get(Calendar.DAY_OF_YEAR)==b.get(Calendar.DAY_OF_YEAR);}
    static String trunc(String s,FontMetrics fm,int maxW){
        if(fm.stringWidth(s)<=maxW)return s;
        while(s.length()>1&&fm.stringWidth(s+"…")>maxW)s=s.substring(0,s.length()-1);return s+"…";}
    @Override public Dimension getPreferredScrollableViewportSize(){return new Dimension(950,550);}
    @Override public int getScrollableUnitIncrement(Rectangle r,int o,int d){return 20;}
    @Override public int getScrollableBlockIncrement(Rectangle r,int o,int d){return HR_H;}
    @Override public boolean getScrollableTracksViewportWidth(){return true;}
    @Override public boolean getScrollableTracksViewportHeight(){return false;}
}

// ═══════════════════════════════════════════════════════════════════════════════
//  DATABASE LAYER  (SQLite via sqlite-jdbc)
// ═══════════════════════════════════════════════════════════════════════════════
class StudyDB {

    private static final File DB_FILE =
            new File(System.getProperty("user.home"), "studyplanner.db");
    private static Connection conn;

    // ── Open / init ────────────────────────────────────────────────────────────
    static void open() {
        try {
            Class.forName("org.sqlite.JDBC");
            conn = DriverManager.getConnection("jdbc:sqlite:" + DB_FILE.getAbsolutePath());
            conn.createStatement().execute("PRAGMA journal_mode=WAL");
            createTables();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null,
                    "DB open error: " + e.getMessage() + "\n\nMake sure sqlite-jdbc.jar is in the classpath.",
                    "Database Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    static void close() {
        try { if (conn != null && !conn.isClosed()) conn.close(); } catch (Exception ignored) {}
    }

    // ── Schema ─────────────────────────────────────────────────────────────────
    private static void createTables() throws SQLException {
        Statement st = conn.createStatement();

        st.execute("""
            CREATE TABLE IF NOT EXISTS subjects (
                id            INTEGER PRIMARY KEY AUTOINCREMENT,
                name          TEXT    NOT NULL,
                deadline      INTEGER NOT NULL,
                priority      TEXT    NOT NULL DEFAULT 'MEDIUM',
                completed     INTEGER NOT NULL DEFAULT 0,
                liked         INTEGER NOT NULL DEFAULT 0,
                pinned        INTEGER NOT NULL DEFAULT 0,
                notes         TEXT    NOT NULL DEFAULT '',
                xp            INTEGER NOT NULL DEFAULT 0,
                pomodoros     INTEGER NOT NULL DEFAULT 0,
                sched_hour    INTEGER NOT NULL DEFAULT -1,
                completed_at  INTEGER
            )""");

        st.execute("""
            CREATE TABLE IF NOT EXISTS quick_notes (
                id      INTEGER PRIMARY KEY AUTOINCREMENT,
                title   TEXT    NOT NULL DEFAULT '',
                body    TEXT    NOT NULL DEFAULT '',
                color_r INTEGER NOT NULL DEFAULT 99,
                color_g INTEGER NOT NULL DEFAULT 102,
                color_b INTEGER NOT NULL DEFAULT 241,
                created INTEGER NOT NULL
            )""");

        st.execute("""
            CREATE TABLE IF NOT EXISTS cal_marks (
                date_key TEXT PRIMARY KEY,
                label    TEXT NOT NULL DEFAULT ''
            )""");

        st.execute("""
            CREATE TABLE IF NOT EXISTS app_settings (
                key   TEXT PRIMARY KEY,
                value TEXT
            )""");

        st.close();
    }

    // ── Subjects ───────────────────────────────────────────────────────────────
    static List<Subject> loadSubjects() {
        List<Subject> result = new ArrayList<>();
        try {
            ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT * FROM subjects ORDER BY pinned DESC, id ASC");
            while (rs.next()) {
                Subject s = new Subject(rs.getString("name"),
                        new Date(rs.getLong("deadline")),
                        rs.getString("priority"));
                s.dbId         = rs.getInt("id");
                s.completed    = rs.getInt("completed") == 1;
                s.liked        = rs.getInt("liked") == 1;
                s.pinned       = rs.getInt("pinned") == 1;
                s.notes        = rs.getString("notes");
                s.xp           = rs.getInt("xp");
                s.pomodorosDone= rs.getInt("pomodoros");
                s.schedHour    = rs.getInt("sched_hour");
                long ca = rs.getLong("completed_at");
                s.completedDate = ca > 0 ? new Date(ca) : null;
                result.add(s);
            }
        } catch (Exception e) { e.printStackTrace(); }
        return result;
    }

    static void insertSubject(Subject s) {
        try {
            PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO subjects(name,deadline,priority,completed,liked,pinned,notes,xp,pomodoros,sched_hour,completed_at)" +
                " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, s.name);
            ps.setLong  (2, s.deadline.getTime());
            ps.setString(3, s.priority);
            ps.setInt   (4, s.completed ? 1 : 0);
            ps.setInt   (5, s.liked     ? 1 : 0);
            ps.setInt   (6, s.pinned    ? 1 : 0);
            ps.setString(7, s.notes);
            ps.setInt   (8, s.xp);
            ps.setInt   (9, s.pomodorosDone);
            ps.setInt   (10, s.schedHour);
            ps.setObject(11, s.completedDate != null ? s.completedDate.getTime() : null);
            ps.executeUpdate();
            ResultSet keys = ps.getGeneratedKeys();
            if (keys.next()) s.dbId = keys.getInt(1);
        } catch (Exception e) { e.printStackTrace(); }
    }

    static void updateSubject(Subject s) {
        if (s.dbId < 0) { insertSubject(s); return; }
        try {
            PreparedStatement ps = conn.prepareStatement(
                "UPDATE subjects SET name=?,deadline=?,priority=?,completed=?,liked=?,pinned=?," +
                "notes=?,xp=?,pomodoros=?,sched_hour=?,completed_at=? WHERE id=?");
            ps.setString(1, s.name);
            ps.setLong  (2, s.deadline.getTime());
            ps.setString(3, s.priority);
            ps.setInt   (4, s.completed ? 1 : 0);
            ps.setInt   (5, s.liked     ? 1 : 0);
            ps.setInt   (6, s.pinned    ? 1 : 0);
            ps.setString(7, s.notes);
            ps.setInt   (8, s.xp);
            ps.setInt   (9, s.pomodorosDone);
            ps.setInt   (10, s.schedHour);
            ps.setObject(11, s.completedDate != null ? s.completedDate.getTime() : null);
            ps.setInt   (12, s.dbId);
            ps.executeUpdate();
        } catch (Exception e) { e.printStackTrace(); }
    }

    static void deleteSubject(int dbId) {
        try {
            PreparedStatement ps = conn.prepareStatement("DELETE FROM subjects WHERE id=?");
            ps.setInt(1, dbId); ps.executeUpdate();
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ── Quick Notes ────────────────────────────────────────────────────────────
    static List<QuickNote> loadNotes() {
        List<QuickNote> result = new ArrayList<>();
        try {
            ResultSet rs = conn.createStatement().executeQuery(
                    "SELECT * FROM quick_notes ORDER BY created DESC");
            while (rs.next()) {
                Color c = new Color(rs.getInt("color_r"), rs.getInt("color_g"), rs.getInt("color_b"));
                QuickNote qn = new QuickNote(rs.getString("title"), rs.getString("body"), c);
                qn.dbId    = rs.getInt("id");
                qn.created = rs.getLong("created");
                result.add(qn);
            }
        } catch (Exception e) { e.printStackTrace(); }
        return result;
    }

    static void upsertNote(QuickNote qn) {
        try {
            if (qn.dbId < 0) {
                PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO quick_notes(title,body,color_r,color_g,color_b,created) VALUES(?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, qn.title); ps.setString(2, qn.body);
                ps.setInt(3, qn.color.getRed()); ps.setInt(4, qn.color.getGreen()); ps.setInt(5, qn.color.getBlue());
                ps.setLong(6, qn.created); ps.executeUpdate();
                ResultSet k = ps.getGeneratedKeys(); if (k.next()) qn.dbId = k.getInt(1);
            } else {
                PreparedStatement ps = conn.prepareStatement(
                    "UPDATE quick_notes SET title=?,body=?,color_r=?,color_g=?,color_b=? WHERE id=?");
                ps.setString(1, qn.title); ps.setString(2, qn.body);
                ps.setInt(3, qn.color.getRed()); ps.setInt(4, qn.color.getGreen()); ps.setInt(5, qn.color.getBlue());
                ps.setInt(6, qn.dbId); ps.executeUpdate();
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    static void deleteNote(int dbId) {
        try {
            PreparedStatement ps = conn.prepareStatement("DELETE FROM quick_notes WHERE id=?");
            ps.setInt(1, dbId); ps.executeUpdate();
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ── Cal Marks ──────────────────────────────────────────────────────────────
    static Map<String,String> loadCalMarks() {
        Map<String,String> m = new HashMap<>();
        try {
            ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM cal_marks");
            while (rs.next()) m.put(rs.getString("date_key"), rs.getString("label"));
        } catch (Exception e) { e.printStackTrace(); }
        return m;
    }

    static void setCalMark(String dateKey, String label) {
        try {
            PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO cal_marks(date_key,label) VALUES(?,?)");
            ps.setString(1, dateKey); ps.setString(2, label); ps.executeUpdate();
        } catch (Exception e) { e.printStackTrace(); }
    }

    static void deleteCalMark(String dateKey) {
        try {
            PreparedStatement ps = conn.prepareStatement("DELETE FROM cal_marks WHERE date_key=?");
            ps.setString(1, dateKey); ps.executeUpdate();
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ── App Settings ───────────────────────────────────────────────────────────
    static String getSetting(String key, String def) {
        try {
            PreparedStatement ps = conn.prepareStatement("SELECT value FROM app_settings WHERE key=?");
            ps.setString(1, key); ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString("value");
        } catch (Exception e) { e.printStackTrace(); }
        return def;
    }

    static void setSetting(String key, String value) {
        try {
            PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO app_settings(key,value) VALUES(?,?)");
            ps.setString(1, key); ps.setString(2, value); ps.executeUpdate();
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ── Migration: import old .dat binary file ─────────────────────────────────
    @SuppressWarnings("unchecked")
    static boolean migrateFromDatFile(File datFile,
            List<Subject> subjects, List<QuickNote> notes, Map<String,String> marks) {
        if (!datFile.exists()) return false;
        try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(datFile))) {
            List<Subject>       importedS = (List<Subject>)      in.readObject();
            List<QuickNote>     importedN = (List<QuickNote>)     in.readObject();
            Map<String,String>  importedC = (Map<String,String>)  in.readObject();
            int streak  = in.readInt();
            int xp      = in.readInt();
            Object ld   = in.readObject();
            String bg   = null;
            try { bg = (String) in.readObject(); } catch (Exception ignored) {}

            // Persist into SQLite
            for (Subject s   : importedS) insertSubject(s);
            for (QuickNote q : importedN) upsertNote(q);
            for (Map.Entry<String,String> e : importedC.entrySet()) setCalMark(e.getKey(), e.getValue());
            setSetting("streak",  String.valueOf(streak));
            setSetting("total_xp",String.valueOf(xp));
            if (ld instanceof Date) setSetting("last_study", String.valueOf(((Date)ld).getTime()));
            if (bg != null) setSetting("bg_image_path", bg);

            subjects.addAll(importedS);
            notes.addAll(importedN);
            marks.putAll(importedC);

            // Rename old file so it's not imported twice
            datFile.renameTo(new File(datFile.getParent(), "studydata.dat.migrated"));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  MAIN APP
// ═══════════════════════════════════════════════════════════════════════════════
public class StudyPlannerApp {

    // Legacy dat file (kept only for one-time migration)
    static final File LEGACY_DAT = new File(System.getProperty("user.home"), "studydata.dat");

    static ArrayList<Subject>     list       = new ArrayList<>();
    static ArrayList<QuickNote>   quickNotes = new ArrayList<>();
    static HashMap<String,String> calMarks   = new HashMap<>();
    static Date lastStudyDate = null;
    static String bgImagePath = null;
    static int studyStreak = 0, totalXP = 0, level = 1;

    static DefaultTableModel model;
    static JProgressBar progressBar;
    static JLabel streakLabel, quoteLabel, xpLabel;
    static TrayIcon trayIcon;
    static JFrame frame;
    static JTable table;
    static BackgroundPanel rootPanel;
    static WeekCalendarPanel weekCal;

    // ── Themes ──────────────────────────────────────────────────────────────────
    static final Theme[] THEMES = {
        new Theme("🌙 Dark",
            new Color(12,12,24),new Color(20,20,38),new Color(30,30,52),
            new Color(230,230,255),new Color(130,130,175),new Color(99,102,241),new Color(52,211,153)),
        new Theme("☀️ Light",
            new Color(242,244,255),new Color(255,255,255),new Color(230,232,248),
            new Color(20,20,50),new Color(100,100,145),new Color(79,70,229),new Color(16,185,129)),
        new Theme("🌿 Forest",
            new Color(8,20,12),new Color(14,30,18),new Color(20,42,26),
            new Color(200,240,210),new Color(120,180,135),new Color(52,211,100),new Color(251,191,36)),
        new Theme("🌅 Sunset",
            new Color(25,10,10),new Color(38,18,15),new Color(52,26,20),
            new Color(255,230,210),new Color(200,150,120),new Color(251,113,52),new Color(251,191,36)),
        new Theme("🌸 Blossom",
            new Color(255,240,245),new Color(255,228,235),new Color(255,210,225),
            new Color(100,20,50),new Color(180,90,120),new Color(219,39,119),new Color(244,114,182)),
        new Theme("🌌 Cosmos",
            new Color(5,5,25),new Color(10,10,40),new Color(15,15,55),
            new Color(200,210,255),new Color(120,130,200),new Color(139,92,246),new Color(244,114,182)),
    };
    static Theme thm = THEMES[0];

    static final Color DANGER     = new Color(239,68,68);
    static final Color LIKE_COLOR = new Color(236,72,153);
    static final Color PIN_COLOR  = new Color(250,204,21);
    static final Color FOCUS_COLOR= new Color(56,189,248);
    static final Color XP_COLOR   = new Color(251,146,60);
    static final Color CHIP_RED   = new Color(220,38,38);
    static final Color CHIP_YEL   = new Color(202,138,4);
    static final Color CHIP_GRN   = new Color(22,163,74);
    static final Color[] NOTE_COLORS = {
        new Color(99,102,241),new Color(52,211,153),new Color(251,113,52),
        new Color(239,68,118),new Color(250,204,21),new Color(56,189,248)
    };
    static final String[] QUOTES = {
        "\"Discipline is doing it even when you don't feel like it.\"",
        "\"You don't rise to your goals — you fall to your systems.\"",
        "\"Hard work beats talent when talent doesn't work hard.\"",
        "\"Study now. Flex later.\"","\"Big brain hours. Let's go.\"",
        "\"One more chapter. One more problem.\"",
        "\"Grind in silence. Let your grades speak.\""
    };

    static JPanel   notesPanel, notesListPanel;
    static boolean  notesSidebarOpen = false;
    static JPanel   notifChipsPanel;
    static JTextField subjectField;

    // ─────────────────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception e) {}

        // Open database first
        StudyDB.open();
        loadData();
        setupTray();

        frame = new JFrame("📚 Study Grind — Smart Planner");
        frame.setSize(1280, 800);
        frame.setMinimumSize(new Dimension(1024, 660));
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { StudyDB.close(); System.exit(0); }
        });

        rootPanel = new BackgroundPanel(new BorderLayout());
        rootPanel.setBackground(thm.bg);
        frame.setContentPane(rootPanel);

        // ── Top bar ─────────────────────────────────────────────────────────────
        GlassPanel topBar = new GlassPanel(new BorderLayout(), alphaColor(thm.panel, 230));
        topBar.setPreferredSize(new Dimension(0, 64));
        topBar.setBorder(new EmptyBorder(0, 18, 0, 18));
        JLabel titleLbl = new JLabel("📚 Study Grind");
        titleLbl.setFont(new Font("SansSerif", Font.BOLD, 22));
        titleLbl.setForeground(thm.accent);
        JPanel topRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 0));
        topRight.setOpaque(false);
        streakLabel = new JLabel("🔥 " + studyStreak + " days");
        streakLabel.setFont(new Font("SansSerif", Font.PLAIN, 13));
        streakLabel.setForeground(new Color(255, 160, 50));
        xpLabel = new JLabel("⭐ LVL " + level + " | " + totalXP + " XP");
        xpLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
        xpLabel.setForeground(XP_COLOR);

        // DB status indicator
        JLabel dbLabel = new JLabel("🗄️ DB");
        dbLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        dbLabel.setForeground(new Color(52, 211, 153));
        dbLabel.setToolTipText("Data stored in SQLite: " +
                new File(System.getProperty("user.home"), "studyplanner.db").getAbsolutePath());

        String[] tnames = Arrays.stream(THEMES).map(t -> t.name).toArray(String[]::new);
        JComboBox<String> themeCombo = new JComboBox<>(tnames);
        themeCombo.setFont(new Font("SansSerif", Font.BOLD, 12));
        themeCombo.setFocusable(false);
        themeCombo.addActionListener(e -> applyTheme(themeCombo.getSelectedIndex()));
        JButton bgBtn      = accentButton("🖼️ BG",      new Color(168, 85, 247));
        JButton calendarBtn= accentButton("📅 Calendar", new Color(56, 189, 248));
        JButton notesToggle= accentButton("🗒️ Notes",   new Color(52, 211, 153));
        bgBtn.addActionListener(e -> chooseBgImage());
        calendarBtn.addActionListener(e -> showWeekCalendarView());
        notesToggle.addActionListener(e -> toggleNotesSidebar());
        topRight.add(dbLabel); topRight.add(xpLabel); topRight.add(streakLabel);
        topRight.add(themeCombo); topRight.add(bgBtn); topRight.add(calendarBtn); topRight.add(notesToggle);
        topBar.add(titleLbl, BorderLayout.WEST);
        topBar.add(topRight, BorderLayout.EAST);

        GlassPanel quoteBanner = new GlassPanel(new FlowLayout(FlowLayout.LEFT), alphaColor(thm.panel, 180));
        quoteBanner.setPreferredSize(new Dimension(0, 26));
        quoteBanner.setBorder(new EmptyBorder(0, 18, 0, 18));
        quoteLabel = new JLabel(randomQuote());
        quoteLabel.setFont(new Font("SansSerif", Font.ITALIC, 12));
        quoteLabel.setForeground(thm.sub);
        quoteBanner.add(quoteLabel);
        JPanel topSection = new JPanel(new BorderLayout()); topSection.setOpaque(false);
        topSection.add(topBar, BorderLayout.NORTH); topSection.add(quoteBanner, BorderLayout.SOUTH);

        // ── Input row ──────────────────────────────────────────────────────────
        GlassPanel inputCard = new GlassPanel(new FlowLayout(FlowLayout.LEFT, 7, 5), alphaColor(thm.card, 210));
        inputCard.setBorder(new EmptyBorder(2, 8, 2, 8));
        subjectField = styledField(14);
        SpinnerDateModel dm = new SpinnerDateModel();
        JSpinner dateSpinner = new JSpinner(dm);
        dateSpinner.setEditor(new JSpinner.DateEditor(dateSpinner, "dd/MM/yyyy"));
        styleSpinner(dateSpinner);
        JComboBox<String> priorityBox = new JComboBox<>(new String[]{"HIGH","MEDIUM","LOW"});
        styleCombo(priorityBox);
        JTextField searchField = styledField(10);
        searchField.putClientProperty("JTextField.placeholderText","🔍 Search…");
        inputCard.add(lbl("Subject:")); inputCard.add(subjectField);
        inputCard.add(lbl("Deadline:")); inputCard.add(dateSpinner);
        inputCard.add(lbl("Priority:")); inputCard.add(priorityBox);
        inputCard.add(lbl("Search:")); inputCard.add(searchField);

        // ── Button Strip ───────────────────────────────────────────────────────
        JButton addBtn    = accentButton("➕ Add",      thm.accent);
        JButton planBtn   = accentButton("📋 Plan",     new Color(139,92,246));
        JButton doneBtn   = accentButton("✅ Done",     new Color(52,211,153));
        JButton likeBtn   = accentButton("❤️ Like",    LIKE_COLOR);
        JButton pinBtn    = accentButton("📌 Pin",      PIN_COLOR);
        JButton suggestBtn= accentButton("🎯 Suggest",  new Color(56,189,248));
        JButton statsBtn  = accentButton("📊 Stats",    new Color(99,102,241));
        JButton focusBtn  = accentButton("🧠 Focus",    FOCUS_COLOR);
        JButton notesBtn  = accentButton("📝 Notes",    new Color(251,191,36));
        JButton exportBtn = accentButton("📤 Export",   new Color(34,197,94));
        JButton editBtn   = accentButton("✏️ Edit",    new Color(251,113,52));
        JButton deleteBtn = accentButton("🗑 Delete",   DANGER);
        JButton saveBtn   = accentButton("💾 Save",     new Color(56,189,248));
        JButton loadBtn   = accentButton("📂 Reload",   new Color(139,92,246));
        JButton voiceBtn  = accentButton("🎤 Voice",    new Color(239,68,118));
        JButton quoteBtn  = accentButton("💬 Quote",    new Color(251,191,36));
        JButton dbViewBtn = accentButton("🗄️ DB Info",  new Color(52,211,153));

        GlassPanel btnStrip = new GlassPanel(new FlowLayout(FlowLayout.LEFT,5,4), alphaColor(thm.card,200));
        for (JButton b : new JButton[]{addBtn,planBtn,doneBtn,likeBtn,pinBtn,suggestBtn,
                statsBtn,focusBtn,notesBtn,exportBtn,editBtn,deleteBtn,saveBtn,loadBtn,voiceBtn,quoteBtn,dbViewBtn})
            btnStrip.add(b);

        JScrollPane btnScroll = new JScrollPane(btnStrip,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        btnScroll.setBorder(BorderFactory.createEmptyBorder());
        btnScroll.getHorizontalScrollBar().setUnitIncrement(20);
        btnScroll.setPreferredSize(new Dimension(0, btnStrip.getPreferredSize().height + 10));
        btnScroll.getViewport().setOpaque(false); btnScroll.setOpaque(false);

        JPanel toolbarContainer = new JPanel(new BorderLayout(0,3)); toolbarContainer.setOpaque(false);
        toolbarContainer.add(inputCard, BorderLayout.NORTH);
        toolbarContainer.add(btnScroll, BorderLayout.CENTER);

        // ── Table ──────────────────────────────────────────────────────────────
        String[] cols = {"📌","Subject","Deadline","Priority","Status","DaysLeft","❤️","⭐ XP"};
        model = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        table = new JTable(model);
        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter); styleTable(table); setupDragDrop();
        table.getColumnModel().getColumn(5).setMinWidth(0);
        table.getColumnModel().getColumn(5).setMaxWidth(0);
        table.getColumnModel().getColumn(5).setPreferredWidth(0);
        table.getColumnModel().getColumn(0).setPreferredWidth(28);
        table.getColumnModel().getColumn(0).setMaxWidth(36);
        table.getColumnModel().getColumn(6).setPreferredWidth(36);
        table.getColumnModel().getColumn(6).setMaxWidth(44);
        table.getColumnModel().getColumn(7).setPreferredWidth(64);
        table.getColumnModel().getColumn(7).setMaxWidth(76);
        SubjectTableRenderer renderer = new SubjectTableRenderer();
        for (int i = 0; i < table.getColumnCount(); i++)
            table.getColumnModel().getColumn(i).setCellRenderer(renderer);
        JScrollPane pane = new JScrollPane(table);
        pane.setBorder(BorderFactory.createLineBorder(new Color(60,60,100,100)));
        pane.getViewport().setBackground(alphaColor(thm.card,200)); pane.setOpaque(false);

        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void f() { String t = searchField.getText().trim();
                sorter.setRowFilter(t.isEmpty()?null:RowFilter.regexFilter("(?i)"+t,1)); }
            public void insertUpdate(javax.swing.event.DocumentEvent e){f();}
            public void removeUpdate(javax.swing.event.DocumentEvent e){f();}
            public void changedUpdate(javax.swing.event.DocumentEvent e){f();}
        });

        progressBar = new JProgressBar(0,100); progressBar.setStringPainted(true);
        progressBar.setForeground(thm.accent2); progressBar.setFont(new Font("SansSerif",Font.BOLD,11));

        // ── Notification bar ───────────────────────────────────────────────────
        GlassPanel notifBar = new GlassPanel(new BorderLayout(4,0), alphaColor(thm.panel,210));
        notifBar.setBorder(new EmptyBorder(5,12,5,12));
        JLabel notifTitle = new JLabel("🔔 Alerts: ");
        notifTitle.setFont(new Font("SansSerif",Font.BOLD,12)); notifTitle.setForeground(thm.accent);
        notifChipsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT,5,0)); notifChipsPanel.setOpaque(false);
        JScrollPane notifScroll = new JScrollPane(notifChipsPanel,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        notifScroll.setBorder(BorderFactory.createEmptyBorder());
        notifScroll.getViewport().setOpaque(false); notifScroll.setOpaque(false);
        notifBar.add(notifTitle, BorderLayout.WEST); notifBar.add(notifScroll, BorderLayout.CENTER);

        JPanel bottomArea = new JPanel(new BorderLayout(0,2)); bottomArea.setOpaque(false);
        JPanel progressRow = new JPanel(new BorderLayout(8,0)); progressRow.setOpaque(false);
        progressRow.setBorder(new EmptyBorder(4,14,8,14)); progressRow.add(progressBar, BorderLayout.CENTER);
        bottomArea.add(notifBar, BorderLayout.NORTH); bottomArea.add(progressRow, BorderLayout.SOUTH);

        JPanel tableArea = new JPanel(new BorderLayout(0,8)); tableArea.setOpaque(false);
        tableArea.setBorder(new EmptyBorder(8,14,8,6));
        tableArea.add(toolbarContainer, BorderLayout.NORTH);
        tableArea.add(pane, BorderLayout.CENTER);
        buildNotesSidebar();
        JPanel centerRow = new JPanel(new BorderLayout()); centerRow.setOpaque(false);
        centerRow.add(tableArea, BorderLayout.CENTER); centerRow.add(notesPanel, BorderLayout.EAST);
        rootPanel.add(topSection, BorderLayout.NORTH);
        rootPanel.add(centerRow, BorderLayout.CENTER);
        rootPanel.add(bottomArea, BorderLayout.SOUTH);

        // Restore background
        if (bgImagePath != null && !bgImagePath.isEmpty() && !bgImagePath.startsWith("__preset__")) {
            try { rootPanel.setBackground(ImageIO.read(new File(bgImagePath))); }
            catch (Exception e) { bgImagePath = null; }
        } else if (bgImagePath != null && bgImagePath.startsWith("__preset__")) {
            try { int pi = Integer.parseInt(bgImagePath.replace("__preset__",""));
                rootPanel.setBackground(generateGradientBg(pi)); }
            catch (Exception e) { bgImagePath = null; }
        }

        // ── Button Listeners ───────────────────────────────────────────────────
        addBtn.addActionListener(e -> {
            String name = subjectField.getText().trim();
            if (name.isEmpty()) { shake(subjectField); return; }
            Subject s = new Subject(name, (Date)dateSpinner.getValue(), (String)priorityBox.getSelectedItem());
            StudyDB.insertSubject(s);
            list.add(s);
            subjectField.setText(""); refreshTable(); refreshNotifBar();
            sendNotification("Added! 📚","\""+name+"\" added.");
        });

        planBtn.addActionListener(e -> {
            list.sort(Comparator.comparing((Subject s) -> !s.pinned)
                    .thenComparingInt(s -> Subject.priorityWeight(s.priority))
                    .thenComparingLong(Subject::getDeadlineTime));
            refreshTable();
        });

        doneBtn.addActionListener(e -> {
            int vr = table.getSelectedRow(); if (vr<0){warn("Select a subject first!");return;}
            int mr = table.convertRowIndexToModel(vr); Subject s = list.get(mr);
            if (s.completed){warn("Already done!");return;}
            s.completed = true; s.completedDate = new Date(); lastStudyDate = new Date();
            int earned = "HIGH".equals(s.priority)?20:"MEDIUM".equals(s.priority)?10:5;
            s.xp += earned; totalXP += earned; studyStreak++; level = 1 + totalXP/100;
            StudyDB.updateSubject(s);
            StudyDB.setSetting("streak", String.valueOf(studyStreak));
            StudyDB.setSetting("total_xp", String.valueOf(totalXP));
            StudyDB.setSetting("last_study", String.valueOf(lastStudyDate.getTime()));
            streakLabel.setText("🔥 "+studyStreak+" days"); xpLabel.setText("⭐ LVL "+level+" | "+totalXP+" XP");
            refreshTable(); refreshNotifBar();
            sendNotification("Done! ✅ +"+earned+" XP","\""+s.name+"\" complete!");
        });

        likeBtn.addActionListener(e -> {
            int vr = table.getSelectedRow(); if (vr<0){warn("!");return;}
            Subject s = list.get(table.convertRowIndexToModel(vr));
            s.liked ^= true; StudyDB.updateSubject(s); refreshTable();
        });

        pinBtn.addActionListener(e -> {
            int vr = table.getSelectedRow(); if (vr<0){warn("Select a subject!");return;}
            int mr = table.convertRowIndexToModel(vr); Subject s = list.get(mr);
            s.pinned = !s.pinned; StudyDB.updateSubject(s); refreshTable();
            sendNotification(s.pinned?"Pinned 📌":"Unpinned","\""+s.name+"\""+(s.pinned?" pinned.":" unpinned."));
        });

        suggestBtn.addActionListener(e -> {
            Subject best = getNextBestTask();
            if (best==null){JOptionPane.showMessageDialog(frame,"🎉 All done!","",JOptionPane.INFORMATION_MESSAGE);return;}
            long d = best.daysLeft(); String urg = d<0?"⚠️ OVERDUE!":d==0?"⚡ TODAY!":d==1?"🔥 TOMORROW!":"📅 "+d+"d";
            JOptionPane.showMessageDialog(frame,"🎯 Focus on:\n\n  📚 "+best.name+"\n  🏷️ "+best.priority+"\n  "+urg,"Smart Suggest",JOptionPane.INFORMATION_MESSAGE);
        });

        statsBtn.addActionListener(e -> showStats());

        focusBtn.addActionListener(e -> {
            int vr = table.getSelectedRow(); Subject t2 = vr>=0?list.get(table.convertRowIndexToModel(vr)):getNextBestTask();
            if (t2==null){warn("No tasks!");return;} showFocusMode(t2);
        });

        notesBtn.addActionListener(e -> {
            int vr = table.getSelectedRow(); if (vr<0){warn("Select a subject first!");return;}
            int mr = table.convertRowIndexToModel(vr); Subject s = list.get(mr);
            JTextArea area = new JTextArea(s.notes,8,30); area.setLineWrap(true); area.setWrapStyleWord(true);
            area.setBackground(thm.card); area.setForeground(thm.fg); area.setCaretColor(thm.accent);
            area.setFont(new Font("SansSerif",Font.PLAIN,13));
            if (JOptionPane.showConfirmDialog(frame,new JScrollPane(area),"🧾 Notes — "+s.name,
                    JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)==JOptionPane.OK_OPTION)
            { s.notes = area.getText(); StudyDB.updateSubject(s); refreshTable(); }
        });

        exportBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(); fc.setSelectedFile(new File("study_report.txt"));
            if (fc.showSaveDialog(frame)==JFileChooser.APPROVE_OPTION) {
                try (PrintWriter pw = new PrintWriter(fc.getSelectedFile())) {
                    pw.println("=== STUDY GRIND REPORT — "+new Date()+" ===\n");
                    SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy");
                    for (Subject s : list) {
                        pw.println("Subject  : "+s.name); pw.println("Deadline : "+sdf.format(s.deadline));
                        pw.println("Priority : "+s.priority); pw.println("Status   : "+(s.completed?"Done":"Pending"));
                        pw.println("XP       : "+s.xp); if (!s.notes.isEmpty()) pw.println("Notes    : "+s.notes);
                        pw.println("---");
                    }
                    pw.println("\nTotal XP: "+totalXP+"  Level: "+level+"  Streak: "+studyStreak+" days");
                    JOptionPane.showMessageDialog(frame,"Exported: "+fc.getSelectedFile().getAbsolutePath());
                } catch (Exception ex) { JOptionPane.showMessageDialog(frame,"Export failed: "+ex.getMessage()); }
            }
        });

        editBtn.addActionListener(e -> {
            int vr = table.getSelectedRow(); if (vr<0){warn("Select a subject first!");return;}
            int mr = table.convertRowIndexToModel(vr); Subject s = list.get(mr);
            JTextField nf = new JTextField(s.name,18);
            SpinnerDateModel sdm = new SpinnerDateModel(s.deadline,null,null,Calendar.DAY_OF_MONTH);
            JSpinner ds = new JSpinner(sdm); ds.setEditor(new JSpinner.DateEditor(ds,"dd/MM/yyyy"));
            JComboBox<String> pb = new JComboBox<>(new String[]{"HIGH","MEDIUM","LOW"}); pb.setSelectedItem(s.priority);
            JPanel dlg = new JPanel(new GridLayout(3,2,8,8));
            dlg.add(new JLabel("Subject:")); dlg.add(nf); dlg.add(new JLabel("Deadline:")); dlg.add(ds);
            dlg.add(new JLabel("Priority:")); dlg.add(pb);
            if (JOptionPane.showConfirmDialog(frame,dlg,"Edit",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)==JOptionPane.OK_OPTION) {
                s.name = nf.getText().trim(); s.deadline = (Date)sdm.getValue(); s.priority = (String)pb.getSelectedItem();
                StudyDB.updateSubject(s); refreshTable(); refreshNotifBar();
            }
        });

        deleteBtn.addActionListener(e -> {
            int vr = table.getSelectedRow(); if (vr<0){warn("Select a subject first!");return;}
            int mr = table.convertRowIndexToModel(vr);
            if (JOptionPane.showConfirmDialog(frame,"Delete \""+list.get(mr).name+"\"?","Confirm",
                    JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION) {
                Subject removed = list.remove(mr);
                StudyDB.deleteSubject(removed.dbId);
                refreshTable(); refreshNotifBar();
            }
        });

        saveBtn.addActionListener(e -> {
            // With SQLite, saves happen automatically; just confirm to user
            saveData();
            sendNotification("Saved 💾","All data is live in SQLite DB.");
            JOptionPane.showMessageDialog(frame,"✅ Data auto-saved to:\n"+
                    new File(System.getProperty("user.home"),"studyplanner.db").getAbsolutePath(),
                    "Saved",JOptionPane.INFORMATION_MESSAGE);
        });

        loadBtn.addActionListener(e -> {
            list.clear(); quickNotes.clear(); calMarks.clear();
            list.addAll(StudyDB.loadSubjects());
            quickNotes.addAll(StudyDB.loadNotes());
            calMarks.putAll(StudyDB.loadCalMarks());
            studyStreak = Integer.parseInt(StudyDB.getSetting("streak","0"));
            totalXP     = Integer.parseInt(StudyDB.getSetting("total_xp","0"));
            level = 1 + totalXP/100;
            streakLabel.setText("🔥 "+studyStreak+" days"); xpLabel.setText("⭐ LVL "+level+" | "+totalXP+" XP");
            refreshTable(); refreshNotesList(); refreshNotifBar();
            sendNotification("Reloaded 📂","Data reloaded from DB.");
        });

        quoteBtn.addActionListener(e -> quoteLabel.setText(randomQuote()));
        voiceBtn.addActionListener(e -> startVoiceInput());

        dbViewBtn.addActionListener(e -> showDbInfo());

        // ── Timers ─────────────────────────────────────────────────────────────
        new javax.swing.Timer(10*60*1000, ev -> checkDeadlines()).start();
        new javax.swing.Timer(3000, ev -> checkDeadlines()){{setRepeats(false);start();}};
        new javax.swing.Timer(60_000, ev -> refreshNotifBar()).start();
        new javax.swing.Timer(30*60*1000, ev -> smartNotify()).start();

        refreshTable(); refreshNotifBar(); refreshNotesList();
        frame.setVisible(true);
    }

    // ── DB Info Dialog ────────────────────────────────────────────────────────
    static void showDbInfo() {
        File dbFile = new File(System.getProperty("user.home"), "studyplanner.db");
        String size = dbFile.exists() ? String.format("%.1f KB", dbFile.length()/1024.0) : "not found";
        String msg = "╔════════════════════════════════╗\n" +
                     "║    🗄️  SQLite DATABASE INFO    ║\n" +
                     "╠════════════════════════════════╣\n" +
                     "║  File : studyplanner.db        ║\n" +
                     "║  Path : " + System.getProperty("user.home") + "\n" +
                     "║  Size : " + size + "              ║\n" +
                     "╠════════════════════════════════╣\n" +
                     "║  Tables:                       ║\n" +
                     "║  • subjects   (tasks/subjects) ║\n" +
                     "║  • quick_notes                 ║\n" +
                     "║  • cal_marks                   ║\n" +
                     "║  • app_settings                ║\n" +
                     "╠════════════════════════════════╣\n" +
                     "║  Records : " + list.size() + " subjects, " + quickNotes.size() + " notes    ║\n" +
                     "╚════════════════════════════════╝\n\n" +
                     "Browse with: DB Browser for SQLite\n" +
                     "https://sqlitebrowser.org";
        JOptionPane.showMessageDialog(frame, msg, "Database Info", JOptionPane.INFORMATION_MESSAGE);
    }

    // ── Week Calendar View ─────────────────────────────────────────────────────
    static void showWeekCalendarView() {
        JDialog dlg = new JDialog(frame,"📅 Week Calendar",false);
        dlg.setSize(980,680); dlg.setLayout(new BorderLayout());
        dlg.getContentPane().setBackground(thm.bg); dlg.setLocationRelativeTo(frame);
        weekCal = new WeekCalendarPanel();
        JPanel nav = new JPanel(new BorderLayout()); nav.setBackground(thm.panel);
        nav.setBorder(new EmptyBorder(8,12,8,12));
        JPanel navLeft = new JPanel(new FlowLayout(FlowLayout.LEFT,6,0)); navLeft.setOpaque(false);
        JButton prevBtn = accentButton("◀",thm.accent);
        JButton nextBtn = accentButton("▶",thm.accent);
        JButton todayBtn= accentButton("Today",thm.accent2);
        JLabel monthLbl = new JLabel(weekCal.headerText());
        monthLbl.setFont(new Font("SansSerif",Font.BOLD,16)); monthLbl.setForeground(thm.fg);
        navLeft.add(prevBtn); navLeft.add(nextBtn); navLeft.add(Box.createHorizontalStrut(8)); navLeft.add(monthLbl);
        JPanel navRight = new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0)); navRight.setOpaque(false);
        JButton addEvtBtn = accentButton("➕ Add to Calendar",thm.accent);
        navRight.add(addEvtBtn); navRight.add(todayBtn);
        nav.add(navLeft,BorderLayout.WEST); nav.add(navRight,BorderLayout.EAST);
        JPanel weekNumRow = new JPanel(new FlowLayout(FlowLayout.LEFT,12,3)); weekNumRow.setBackground(thm.bg);
        Calendar now = Calendar.getInstance();
        JLabel weekLbl = new JLabel("Week "+now.get(Calendar.WEEK_OF_YEAR)+" / 52");
        weekLbl.setFont(new Font("SansSerif",Font.PLAIN,11)); weekLbl.setForeground(thm.sub);
        JLabel legendLbl = new JLabel("  🟦 Deadline  🟨 Marked Day  ✏️ Click day to mark");
        legendLbl.setFont(new Font("SansSerif",Font.PLAIN,11)); legendLbl.setForeground(thm.sub);
        weekNumRow.add(weekLbl); weekNumRow.add(legendLbl);
        JPanel topNav = new JPanel(new BorderLayout()); topNav.setBackground(thm.panel);
        topNav.add(nav,BorderLayout.NORTH); topNav.add(weekNumRow,BorderLayout.SOUTH);
        JScrollPane calScroll = new JScrollPane(weekCal,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        calScroll.setBorder(BorderFactory.createEmptyBorder()); calScroll.getViewport().setBackground(thm.bg);
        SwingUtilities.invokeLater(()->{
            int scrollTo=(WeekCalendarPanel.HDR_H+WeekCalendarPanel.ALLDAY_H+(8-WeekCalendarPanel.START_HR)*WeekCalendarPanel.HR_H)-20;
            calScroll.getVerticalScrollBar().setValue(Math.max(0,scrollTo));});
        prevBtn.addActionListener(e->{weekCal.prevWeek();monthLbl.setText(weekCal.headerText());});
        nextBtn.addActionListener(e->{weekCal.nextWeek();monthLbl.setText(weekCal.headerText());});
        todayBtn.addActionListener(e->{weekCal.goToday();monthLbl.setText(weekCal.headerText());
            SwingUtilities.invokeLater(()->{int sy=(WeekCalendarPanel.HDR_H+WeekCalendarPanel.ALLDAY_H+(8-WeekCalendarPanel.START_HR)*WeekCalendarPanel.HR_H)-20;
                calScroll.getVerticalScrollBar().setValue(Math.max(0,sy));});});
        addEvtBtn.addActionListener(e->{if(list.isEmpty()){warn("Add subjects first.");return;}
            String[] names=list.stream().filter(s->!s.completed).map(s->s.name).toArray(String[]::new);
            if(names.length==0){warn("All subjects completed!");return;}
            JOptionPane.showInputDialog(dlg,"Choose subject:","Add to Calendar",JOptionPane.PLAIN_MESSAGE,null,names,names[0]);
            weekCal.repaint();});
        javax.swing.Timer calRefresh=new javax.swing.Timer(60_000,e->weekCal.repaint()); calRefresh.start();
        dlg.addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent e){calRefresh.stop();}});
        dlg.add(topNav,BorderLayout.NORTH); dlg.add(calScroll,BorderLayout.CENTER); dlg.setVisible(true);
    }

    // ── Background Image ───────────────────────────────────────────────────────
    static void chooseBgImage(){
        String[] opts={"📂 Choose Image File","🎨 Built-in Presets","🗑 Remove BG","Cancel"};
        int ch=JOptionPane.showOptionDialog(frame,"Background Image","🖼️ Background",JOptionPane.DEFAULT_OPTION,JOptionPane.PLAIN_MESSAGE,null,opts,opts[0]);
        if(ch==0)pickImageFile(); else if(ch==1)showPresetBgMenu(); else if(ch==2)clearBgImage();
    }
    static void pickImageFile(){
        JFileChooser fc=new JFileChooser();
        fc.addChoosableFileFilter(new FileNameExtensionFilter("Images","jpg","jpeg","png","gif","bmp"));
        fc.setAcceptAllFileFilterUsed(false);
        if(fc.showOpenDialog(frame)==JFileChooser.APPROVE_OPTION){
            try{BufferedImage img=ImageIO.read(fc.getSelectedFile());
                if(img==null){warn("Cannot read image.");return;}
                bgImagePath=fc.getSelectedFile().getAbsolutePath();
                rootPanel.setBackground(img);frame.repaint();saveData();
            }catch(Exception ex){warn("Error: "+ex.getMessage());}
        }
    }
    static void showPresetBgMenu(){
        String[] presets={"🌸 Cherry Blossom","🌊 Ocean Deep","🌿 Jungle Green","🌌 Galaxy Night","🔥 Crimson Fire"};
        int p=JOptionPane.showOptionDialog(frame,"Choose a preset:","🎨 Preset Backgrounds",JOptionPane.DEFAULT_OPTION,JOptionPane.PLAIN_MESSAGE,null,presets,presets[0]);
        if(p<0)return; bgImagePath="__preset__"+p; rootPanel.setBackground(generateGradientBg(p));frame.repaint();saveData();
    }
    static BufferedImage generateGradientBg(int preset){
        int w=1280,h=800;BufferedImage img=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=(Graphics2D)img.getGraphics();
        g.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
        Color[][] pals={{new Color(255,182,193),new Color(255,228,196),new Color(255,192,203)},
            {new Color(0,30,80),new Color(0,80,140),new Color(0,150,200)},
            {new Color(10,40,15),new Color(20,80,30),new Color(50,150,60)},
            {new Color(5,0,30),new Color(30,0,60),new Color(60,0,120)},
            {new Color(60,5,5),new Color(140,20,10),new Color(220,60,10)}};
        Color[] pal=pals[preset];
        g.setPaint(new GradientPaint(0,0,pal[0],w,0,pal[1]));g.fillRect(0,0,w,h);
        g.setPaint(new GradientPaint(0,h/2,new Color(pal[2].getRed(),pal[2].getGreen(),pal[2].getBlue(),0),0,h,pal[2]));g.fillRect(0,0,w,h);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,0.07f));g.setColor(Color.WHITE);
        Random rnd=new Random(preset*1234);for(int i=0;i<18;i++){int sz=rnd.nextInt(220)+60;g.fillOval(rnd.nextInt(w),rnd.nextInt(h),sz,sz);}
        g.dispose();return img;
    }
    static void clearBgImage(){bgImagePath=null;rootPanel.setBackground((BufferedImage)null);rootPanel.setBackground(thm.bg);frame.repaint();saveData();}

    // ── Theme ─────────────────────────────────────────────────────────────────
    static void applyTheme(int idx){
        thm=THEMES[idx]; StudyDB.setSetting("theme",String.valueOf(idx));
        if(frame==null)return;
        if(bgImagePath==null)rootPanel.setBackground(thm.bg);
        if(quoteLabel!=null)quoteLabel.setForeground(thm.sub);
        if(table!=null){table.setBackground(thm.card);table.setForeground(thm.fg);
            table.setSelectionBackground(thm.accent);table.getTableHeader().setBackground(thm.panel);table.getTableHeader().setForeground(thm.accent);}
        if(progressBar!=null){progressBar.setForeground(thm.accent2);progressBar.setBackground(thm.card);}
        if(notesPanel!=null)refreshNotesList();
        if(weekCal!=null)weekCal.repaint();
        refreshNotifBar();frame.repaint();frame.revalidate();
    }

    // ── Quick Notes Sidebar ────────────────────────────────────────────────────
    static void buildNotesSidebar(){
        notesPanel=new JPanel(new BorderLayout(0,4));notesPanel.setOpaque(false);
        notesPanel.setPreferredSize(new Dimension(238,0));
        notesPanel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,1,0,0,new Color(80,80,120,80)),new EmptyBorder(8,8,8,8)));
        notesPanel.setVisible(false);
        JLabel sideTitle=new JLabel("🗒️  Quick Notes");sideTitle.setFont(new Font("SansSerif",Font.BOLD,14));sideTitle.setForeground(thm.accent2);sideTitle.setBorder(new EmptyBorder(0,0,6,0));
        JButton addNoteBtn=accentButton("➕ New Note",thm.accent2);addNoteBtn.addActionListener(e->showNoteEditor(null));
        JPanel sh=new JPanel(new BorderLayout(4,4));sh.setOpaque(false);sh.add(sideTitle,BorderLayout.NORTH);sh.add(addNoteBtn,BorderLayout.SOUTH);
        notesListPanel=new JPanel();notesListPanel.setLayout(new BoxLayout(notesListPanel,BoxLayout.Y_AXIS));notesListPanel.setOpaque(false);
        JScrollPane sc=new JScrollPane(notesListPanel,JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sc.setBorder(BorderFactory.createEmptyBorder());sc.getViewport().setOpaque(false);sc.setOpaque(false);
        notesPanel.add(sh,BorderLayout.NORTH);notesPanel.add(sc,BorderLayout.CENTER);
    }
    static void toggleNotesSidebar(){notesSidebarOpen=!notesSidebarOpen;notesPanel.setVisible(notesSidebarOpen);if(notesSidebarOpen)refreshNotesList();frame.revalidate();}
    static void refreshNotesList(){
        if(notesListPanel==null)return;notesListPanel.removeAll();
        for(int i=0;i<quickNotes.size();i++){final int idx=i;QuickNote qn=quickNotes.get(i);
            Color ac=qn.color!=null?qn.color:NOTE_COLORS[i%NOTE_COLORS.length];
            JPanel card=new JPanel(new BorderLayout(0,3));card.setMaximumSize(new Dimension(228,112));card.setOpaque(true);
            card.setBackground(new Color(ac.getRed(),ac.getGreen(),ac.getBlue(),40));
            card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,3,0,0,ac),new EmptyBorder(5,7,5,7)));
            JLabel title=new JLabel(qn.title.isEmpty()?"(no title)":qn.title);title.setFont(new Font("SansSerif",Font.BOLD,12));title.setForeground(thm.fg);
            JTextArea body=new JTextArea(qn.body);body.setFont(new Font("SansSerif",Font.PLAIN,11));body.setForeground(thm.sub);body.setLineWrap(true);body.setWrapStyleWord(true);body.setEditable(false);body.setOpaque(false);body.setRows(2);
            JPanel btns=new JPanel(new FlowLayout(FlowLayout.RIGHT,3,0));btns.setOpaque(false);
            JButton en=tinyBtn("✏️",ac),dn=tinyBtn("🗑",DANGER);
            en.addActionListener(e->showNoteEditor(idx));
            dn.addActionListener(e->{StudyDB.deleteNote(qn.dbId);quickNotes.remove(idx);refreshNotesList();});
            btns.add(en);btns.add(dn);card.add(title,BorderLayout.NORTH);
            card.add(new JScrollPane(body,JScrollPane.VERTICAL_SCROLLBAR_NEVER,JScrollPane.HORIZONTAL_SCROLLBAR_NEVER),BorderLayout.CENTER);
            card.add(btns,BorderLayout.SOUTH);notesListPanel.add(card);notesListPanel.add(Box.createVerticalStrut(5));}
        if(quickNotes.isEmpty()){JLabel e=new JLabel("<html><center>No notes yet.<br>Click ➕!</center></html>");e.setFont(new Font("SansSerif",Font.ITALIC,11));e.setForeground(thm.sub);e.setAlignmentX(0.5f);notesListPanel.add(e);}
        notesListPanel.revalidate();notesListPanel.repaint();
    }
    static void showNoteEditor(Integer editIndex){
        QuickNote ex=editIndex!=null?quickNotes.get(editIndex):null;
        JTextField tf=new JTextField(ex!=null?ex.title:"",20);JTextArea ba=new JTextArea(ex!=null?ex.body:"",6,20);ba.setLineWrap(true);ba.setWrapStyleWord(true);
        JPanel cr=new JPanel(new FlowLayout(FlowLayout.LEFT,5,0));cr.add(new JLabel("Color: "));
        int[] sc={ex!=null&&ex.color!=null?idxOfColor(ex.color):0};
        JButton[] cbs=new JButton[NOTE_COLORS.length];
        for(int i=0;i<NOTE_COLORS.length;i++){final int ci=i;JButton cb=new JButton("  ");cb.setBackground(NOTE_COLORS[i]);cb.setPreferredSize(new Dimension(22,22));
            cb.setBorder(sc[0]==i?BorderFactory.createLineBorder(Color.WHITE,2):BorderFactory.createEmptyBorder(2,2,2,2));
            cb.addActionListener(e->{sc[0]=ci;for(int j=0;j<cbs.length;j++)cbs[j].setBorder(j==ci?BorderFactory.createLineBorder(Color.WHITE,2):BorderFactory.createEmptyBorder(2,2,2,2));});cbs[i]=cb;cr.add(cb);}
        JPanel dlg=new JPanel(new BorderLayout(4,6));JPanel tr=new JPanel(new BorderLayout(4,0));tr.add(new JLabel("Title:"),BorderLayout.WEST);tr.add(tf,BorderLayout.CENTER);
        dlg.add(tr,BorderLayout.NORTH);dlg.add(new JScrollPane(ba),BorderLayout.CENTER);dlg.add(cr,BorderLayout.SOUTH);dlg.setPreferredSize(new Dimension(310,210));
        int res=JOptionPane.showConfirmDialog(frame,dlg,ex!=null?"✏️ Edit Note":"➕ New Note",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE);
        if(res==JOptionPane.OK_OPTION){Color c=NOTE_COLORS[sc[0]];String t=tf.getText().trim(),b=ba.getText().trim();
            if(ex!=null){ex.title=t;ex.body=b;ex.color=c;StudyDB.upsertNote(ex);}
            else{QuickNote qn=new QuickNote(t,b,c);StudyDB.upsertNote(qn);quickNotes.add(qn);}
            refreshNotesList();}
    }
    static int idxOfColor(Color c){for(int i=0;i<NOTE_COLORS.length;i++)if(NOTE_COLORS[i].equals(c))return i;return 0;}

    // ── Voice Input ────────────────────────────────────────────────────────────
    static void startVoiceInput(){
        JDialog vd=new JDialog(frame,"🎤 Voice",false);vd.setSize(360,200);vd.setLayout(new BorderLayout());
        vd.getContentPane().setBackground(thm.bg);vd.setLocationRelativeTo(frame);
        JLabel sl=new JLabel("🎤  Listening…",JLabel.CENTER);sl.setFont(new Font("SansSerif",Font.BOLD,20));sl.setForeground(new Color(239,68,118));
        JLabel hl=new JLabel("<html><center>Speak your task clearly.<br>Windows Speech Recognition.</center></html>",JLabel.CENTER);hl.setFont(new Font("SansSerif",Font.PLAIN,12));hl.setForeground(thm.sub);
        JPanel wave=new JPanel(){int[] h={8,14,22,30,22,30,14,8,22,14};int t=0;
            {new javax.swing.Timer(80,e->{t++;for(int i=0;i<h.length;i++)h[i]=10+(int)(20*Math.abs(Math.sin(t*0.3+i*0.8)));repaint();}).start();}
            @Override protected void paintComponent(Graphics g){super.paintComponent(g);setOpaque(false);
                int bw=8,gap=4,total=(bw+gap)*h.length,ox=(getWidth()-total)/2;
                for(int i=0;i<h.length;i++){int bar=h[i],y=(getHeight()-bar)/2;g.setColor(new Color(239,68,118,160+i*8));g.fillRoundRect(ox+i*(bw+gap),y,bw,bar,4,4);}}};
        wave.setPreferredSize(new Dimension(0,50));
        JButton cb=accentButton("❌ Cancel",DANGER);cb.addActionListener(e->vd.dispose());
        int[] dot={0};javax.swing.Timer anim=new javax.swing.Timer(500,ev->{dot[0]=(dot[0]+1)%4;sl.setText("🎤  Listening"+".".repeat(dot[0]));});anim.start();
        vd.add(hl,BorderLayout.NORTH);vd.add(sl,BorderLayout.CENTER);JPanel bot=new JPanel(new BorderLayout());bot.setOpaque(false);bot.add(wave,BorderLayout.NORTH);bot.add(cb,BorderLayout.SOUTH);vd.add(bot,BorderLayout.SOUTH);vd.setVisible(true);
        new Thread(()->{try{
            String ps="Add-Type -AssemblyName System.Speech;$r=New-Object System.Speech.Recognition.SpeechRecognitionEngine;$r.SetInputToDefaultAudioDevice();$r.LoadGrammar([System.Speech.Recognition.DictationGrammar]::new());$r.BabbleTimeout=[TimeSpan]::FromSeconds(5);$r.EndSilenceTimeout=[TimeSpan]::FromSeconds(2);$result=$r.Recognize([TimeSpan]::FromSeconds(8));if($result){Write-Output $result.Text}else{Write-Output '__NONE__'}";
            ProcessBuilder pb=new ProcessBuilder("powershell.exe","-NonInteractive","-Command",ps);pb.redirectErrorStream(true);
            Process proc=pb.start();BufferedReader br=new BufferedReader(new InputStreamReader(proc.getInputStream()));
            StringBuilder sb=new StringBuilder();String line;while((line=br.readLine())!=null)sb.append(line).append(" ");proc.waitFor();
            String result=sb.toString().trim();
            SwingUtilities.invokeLater(()->{anim.stop();vd.dispose();
                if(result.equals("__NONE__")||result.isEmpty())JOptionPane.showMessageDialog(frame,"No speech detected.","Voice",JOptionPane.WARNING_MESSAGE);
                else{String clean=result.endsWith(".")?result.substring(0,result.length()-1):result;subjectField.setText(clean);subjectField.requestFocus();
                    JOptionPane.showMessageDialog(frame,"✅ \""+clean+"\"\n\nClick ➕ Add.","Voice",JOptionPane.INFORMATION_MESSAGE);}});
        }catch(Exception ex){SwingUtilities.invokeLater(()->{anim.stop();vd.dispose();
            String fb=JOptionPane.showInputDialog(frame,"Voice unavailable. Type task:","📝 Manual",JOptionPane.PLAIN_MESSAGE);
            if(fb!=null&&!fb.isEmpty())subjectField.setText(fb.trim());});}}).start();
    }

    // ── Notification Bar ──────────────────────────────────────────────────────
    static void refreshNotifBar(){
        if(notifChipsPanel==null)return;notifChipsPanel.removeAll();
        List<Subject> pending=list.stream().filter(s->!s.completed).collect(Collectors.toList());
        if(pending.isEmpty())notifChipsPanel.add(makeChip("✅ All done!",CHIP_GRN));
        else for(Subject s:pending){long d=s.daysLeft();Color col;String icon;
            if(d<=1){col=CHIP_RED;icon=d<0?"⚠️":"🔴";}else if(d<=3){col=CHIP_YEL;icon="🟡";}else{col=CHIP_GRN;icon="🟢";}
            notifChipsPanel.add(makeChip(icon+" "+s.name+" — "+(d<0?Math.abs(d)+"d overdue":d==0?"TODAY!":d+"d left"),col));}
        notifChipsPanel.revalidate();notifChipsPanel.repaint();
    }
    static JLabel makeChip(String text,Color col){
        JLabel c=new JLabel(text);c.setFont(new Font("SansSerif",Font.BOLD,11));c.setForeground(col);
        c.setOpaque(true);c.setBackground(new Color(col.getRed(),col.getGreen(),col.getBlue(),28));
        c.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(col.getRed(),col.getGreen(),col.getBlue(),110),1,true),new EmptyBorder(2,8,2,8)));return c;}
    static void smartNotify(){
        Calendar today=Calendar.getInstance();today.set(Calendar.HOUR_OF_DAY,0);today.set(Calendar.MINUTE,0);today.set(Calendar.SECOND,0);today.set(Calendar.MILLISECOND,0);
        if(lastStudyDate==null||!lastStudyDate.after(today.getTime()))if(!list.isEmpty()){showInAppAlert("📚 You haven't studied today!");sendNotification("Study Reminder 📚","Haven't studied today!");}
        long threeDays=3L*24*60*60*1000;
        for(Subject s:list)if(s.completed&&s.completedDate!=null&&(System.currentTimeMillis()-s.completedDate.getTime())>threeDays){showInAppAlert("🔄 Revise: \""+s.name+"\" — 3+ days ago!");sendNotification("Revise 🔄","\""+s.name+"\" needs revision!");break;}
        list.stream().filter(s->!s.completed&&s.daysLeft()>=0&&s.daysLeft()<=2).findFirst().ifPresent(s->{String m="⚡ \""+s.name+"\" due in "+s.daysLeft()+"d!";showInAppAlert(m);sendNotification("Exam Soon!",m);});
    }
    static void showInAppAlert(String msg){
        JLabel chip=makeChip("💡 "+msg,thm.accent);chip.setForeground(thm.accent);
        notifChipsPanel.add(chip,0);notifChipsPanel.revalidate();notifChipsPanel.repaint();
        new javax.swing.Timer(20_000,ev->{notifChipsPanel.remove(chip);notifChipsPanel.revalidate();notifChipsPanel.repaint();}){{setRepeats(false);start();}};
    }

    // ── Focus Mode ────────────────────────────────────────────────────────────
    static void showFocusMode(Subject target){
        JDialog focus=new JDialog(frame,"🧠 Focus Mode",true);focus.setSize(400,340);
        focus.setLayout(new BorderLayout());focus.getContentPane().setBackground(thm.bg);
        JPanel panel=makePanel(thm.bg);panel.setLayout(new BoxLayout(panel,BoxLayout.Y_AXIS));panel.setBorder(new EmptyBorder(28,36,28,36));
        JLabel tL=centeredLabel("🧠 FOCUS MODE",20,Font.BOLD,FOCUS_COLOR);
        JLabel nL=centeredLabel(target.name,17,Font.BOLD,thm.fg);
        JLabel iL=centeredLabel("Priority: "+target.priority+"  |  "+(target.daysLeft()<0?"OVERDUE":target.daysLeft()+"d left"),12,Font.PLAIN,thm.sub);
        int[] secs={25*60};JLabel timerL=centeredLabel("⏱ 25:00",34,Font.BOLD,new Color(248,113,113));timerL.setFont(new Font("Monospaced",Font.BOLD,34));
        javax.swing.Timer[] ft={null};JButton btn=accentButton("▶ Start",FOCUS_COLOR);btn.setAlignmentX(0.5f);
        btn.addActionListener(ev->{if(ft[0]!=null&&ft[0].isRunning()){ft[0].stop();btn.setText("▶ Resume");}
            else{ft[0]=new javax.swing.Timer(1000,tick->{secs[0]--;timerL.setText(String.format("⏱ %02d:%02d",secs[0]/60,secs[0]%60));
                timerL.setForeground(secs[0]<=60?DANGER:new Color(248,113,113));
                if(secs[0]<=0){ft[0].stop();playBeep();timerL.setText("✅ Done!");
                    // Save pomodoro to DB
                    target.pomodorosDone++; StudyDB.updateSubject(target);
                    sendNotification("Focus Done 🧠","\""+target.name+"\" pomodoro complete!");}});ft[0].start();btn.setText("⏸ Pause");}});
        focus.addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent e){if(ft[0]!=null)ft[0].stop();}});
        panel.add(Box.createVerticalStrut(6));panel.add(tL);panel.add(Box.createVerticalStrut(14));panel.add(nL);panel.add(Box.createVerticalStrut(6));panel.add(iL);panel.add(Box.createVerticalStrut(18));panel.add(timerL);panel.add(Box.createVerticalStrut(14));panel.add(btn);
        focus.add(panel);focus.setLocationRelativeTo(frame);focus.setVisible(true);
    }

    // ── Stats ─────────────────────────────────────────────────────────────────
    static void showStats(){
        long done=list.stream().filter(s->s.completed).count(),overdue=list.stream().filter(s->!s.completed&&s.daysLeft()<0).count();
        long likedC=list.stream().filter(s->s.liked).count(),pinnedC=list.stream().filter(s->s.pinned).count();
        int pct=list.isEmpty()?0:(int)(done*100/list.size());
        JOptionPane.showMessageDialog(frame,
                "╔══════════════════════════╗\n║    📊  STUDY STATISTICS  ║\n╠══════════════════════════╣\n"+
                "║  Total     : "+list.size()+"\n║  Completed : "+done+"\n║  Pending   : "+(list.size()-done)+
                "\n║  Overdue   : "+overdue+"\n║  Liked ❤️  : "+likedC+"\n║  Pinned 📌 : "+pinnedC+"\n║  Done %    : "+pct+"%\n"+
                "╠══════════════════════════╣\n║  🔥 Streak : "+studyStreak+" days\n║  ⭐ XP     : "+totalXP+
                "\n║  🏆 Level  : "+level+"\n║  📝 Notes  : "+quickNotes.size()+"\n║  📅 Marks  : "+calMarks.size()+
                "\n╚══════════════════════════╝","Statistics",JOptionPane.INFORMATION_MESSAGE);
    }

    // ── Drag & Drop ───────────────────────────────────────────────────────────
    static void setupDragDrop(){
        table.setDragEnabled(true);table.setDropMode(DropMode.INSERT_ROWS);
        table.setTransferHandler(new TransferHandler(){
            int dragRow=-1;
            @Override public int getSourceActions(JComponent c){return MOVE;}
            @Override protected Transferable createTransferable(JComponent c){dragRow=table.getSelectedRow();return new StringSelection(String.valueOf(dragRow));}
            @Override public boolean canImport(TransferSupport ts){return ts.isDrop()&&ts.isDataFlavorSupported(DataFlavor.stringFlavor);}
            @Override public boolean importData(TransferSupport ts){
                if(!canImport(ts)||dragRow<0)return false;
                try{int drop=((JTable.DropLocation)ts.getDropLocation()).getRow();
                    int src=table.convertRowIndexToModel(dragRow),tgt=Math.max(0,Math.min(drop,list.size()-1));
                    Subject moved=list.remove(src);list.add(tgt,moved);refreshTable();dragRow=-1;return true;}
                catch(Exception ex){return false;}}});
    }

    // ── Core ─────────────────────────────────────────────────────────────────
    static Subject getNextBestTask(){return list.stream().filter(s->!s.completed).sorted(
            Comparator.comparing((Subject s)->!s.pinned).thenComparingLong(Subject::daysLeft)
                    .thenComparingInt(s->Subject.priorityWeight(s.priority))).findFirst().orElse(null);}

    static void refreshTable(){
        model.setRowCount(0);int done=0;SimpleDateFormat sdf=new SimpleDateFormat("dd/MM/yyyy");
        for(Subject s:list){if(s.completed)done++;
            model.addRow(new Object[]{s.pinned?"📌":"",s.name,sdf.format(s.deadline),s.priority,
                    s.completed?"Done":"Pending",String.valueOf(s.daysLeft()),s.liked?"❤️":"🤍","⭐"+s.xp});}
        int pct=list.isEmpty()?0:done*100/list.size();
        progressBar.setValue(pct);progressBar.setString("Progress: "+done+"/"+list.size()+" ("+pct+"%)");
        progressBar.setForeground(pct==100?thm.accent2:pct>=60?new Color(251,191,36):thm.accent);
    }

    // ── Load / Save (SQLite-backed) ────────────────────────────────────────────
    static void loadData() {
        // Try to migrate old .dat file first
        if (LEGACY_DAT.exists()) {
            boolean migrated = StudyDB.migrateFromDatFile(LEGACY_DAT, list, quickNotes, calMarks);
            if (migrated) {
                JOptionPane.showMessageDialog(null,
                        "✅ Your old study data has been migrated to SQLite!\n\n" +
                        "Old file renamed to: studydata.dat.migrated\n" +
                        "New DB: " + new File(System.getProperty("user.home"),"studyplanner.db"),
                        "Migration Complete", JOptionPane.INFORMATION_MESSAGE);
            }
        } else {
            list.addAll(StudyDB.loadSubjects());
            quickNotes.addAll(StudyDB.loadNotes());
            calMarks.putAll(StudyDB.loadCalMarks());
        }
        studyStreak = Integer.parseInt(StudyDB.getSetting("streak",   "0"));
        totalXP     = Integer.parseInt(StudyDB.getSetting("total_xp", "0"));
        level       = 1 + totalXP / 100;
        String ls   = StudyDB.getSetting("last_study", null);
        if (ls != null) try { lastStudyDate = new Date(Long.parseLong(ls)); } catch(Exception ignored){}
        bgImagePath = StudyDB.getSetting("bg_image_path", null);

        // Restore theme
        try {
            int ti = Integer.parseInt(StudyDB.getSetting("theme","0"));
            if (ti >= 0 && ti < THEMES.length) thm = THEMES[ti];
        } catch (Exception ignored) {}
    }

    // saveData() is now a convenience method — SQLite persists on every action
    static void saveData() {
        StudyDB.setSetting("streak",        String.valueOf(studyStreak));
        StudyDB.setSetting("total_xp",      String.valueOf(totalXP));
        StudyDB.setSetting("bg_image_path", bgImagePath != null ? bgImagePath : "");
        if (lastStudyDate != null) StudyDB.setSetting("last_study", String.valueOf(lastStudyDate.getTime()));
        // Individual record saves are done inline at each action — nothing extra needed
    }

    // ── Misc ──────────────────────────────────────────────────────────────────
    static void checkDeadlines(){for(Subject s:list)if(!s.completed){long d=s.daysLeft();
        if(d<0)sendNotification("Overdue! ⚠️","\""+s.name+"\" overdue by "+Math.abs(d)+"d!");
        else if(d<=1)sendNotification("Due Soon! 🔔","\""+s.name+"\" due in "+d+"d.");}}
    static void playBeep(){new Thread(()->{try{
        AudioFormat fmt=new AudioFormat(44100,8,1,true,true);DataLine.Info info=new DataLine.Info(SourceDataLine.class,fmt);
        if(!AudioSystem.isLineSupported(info))return;SourceDataLine line=(SourceDataLine)AudioSystem.getLine(info);
        line.open(fmt,44100);line.start();byte[] buf=new byte[44100/2];
        for(int i=0;i<buf.length;i++)buf[i]=(byte)(Math.sin(2*Math.PI*880*i/44100)*80);
        line.write(buf,0,buf.length);line.drain();line.close();}catch(Exception e){}}).start();}
    static void setupTray(){if(!SystemTray.isSupported())return;
        try{BufferedImage img=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);
            Graphics2D g=img.createGraphics();g.setColor(new Color(99,102,241));g.fillOval(0,0,16,16);
            g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,10));g.drawString("S",3,12);g.dispose();
            trayIcon=new TrayIcon(img,"Study Planner");trayIcon.setImageAutoSize(true);
            SystemTray.getSystemTray().add(trayIcon);}catch(Exception e){}}
    static void sendNotification(String title,String msg){if(trayIcon!=null)trayIcon.displayMessage(title,msg,TrayIcon.MessageType.INFO);}
    static void shake(JComponent c){Point o=c.getLocation();int[] ofs={-6,6,-4,4,-2,2,0},st={0};
        javax.swing.Timer t=new javax.swing.Timer(30,null);
        t.addActionListener(e->{if(st[0]>=ofs.length){t.stop();c.setLocation(o);return;}c.setLocation(o.x+ofs[st[0]++],o.y);});t.start();}
    static void warn(String msg){JOptionPane.showMessageDialog(frame,msg,"Oops",JOptionPane.WARNING_MESSAGE);}
    static String randomQuote(){return QUOTES[new Random().nextInt(QUOTES.length)];}
    static JLabel centeredLabel(String t,int sz,int st,Color c){
        JLabel l=new JLabel(t,JLabel.CENTER);l.setFont(new Font("SansSerif",st,sz));l.setForeground(c);l.setAlignmentX(0.5f);return l;}
    static Color alphaColor(Color c,int a){return new Color(c.getRed(),c.getGreen(),c.getBlue(),a);}
    static void styleTable(JTable t){t.setBackground(thm.card);t.setForeground(thm.fg);
        t.setFont(new Font("SansSerif",Font.PLAIN,13));t.setRowHeight(32);t.setGridColor(new Color(50,50,70));
        t.setSelectionBackground(thm.accent);t.setSelectionForeground(Color.WHITE);t.setShowGrid(true);
        t.setIntercellSpacing(new Dimension(1,1));t.getTableHeader().setBackground(thm.panel);
        t.getTableHeader().setForeground(thm.accent);t.getTableHeader().setFont(new Font("SansSerif",Font.BOLD,12));}
    static JPanel makePanel(Color bg){JPanel p=new JPanel();p.setBackground(bg);return p;}
    static JLabel lbl(String text){JLabel l=new JLabel(text);l.setForeground(thm.sub);l.setFont(new Font("SansSerif",Font.PLAIN,12));return l;}
    static JTextField styledField(int cols){JTextField f=new JTextField(cols);
        f.setBackground(alphaColor(thm.card,200));f.setForeground(thm.fg);f.setCaretColor(thm.accent);
        f.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(80,80,120,130)),new EmptyBorder(4,8,4,8)));
        f.setFont(new Font("SansSerif",Font.PLAIN,13));return f;}
    static void styleSpinner(JSpinner s){s.setPreferredSize(new Dimension(120,28));s.setFont(new Font("SansSerif",Font.PLAIN,12));
        JComponent ed=s.getEditor();if(ed instanceof JSpinner.DefaultEditor){JTextField tf=((JSpinner.DefaultEditor)ed).getTextField();
            tf.setBackground(alphaColor(thm.card,200));tf.setForeground(thm.fg);tf.setCaretColor(thm.accent);}}
    static void styleCombo(JComboBox<String> c){c.setBackground(thm.card);c.setForeground(thm.fg);c.setFont(new Font("SansSerif",Font.PLAIN,12));c.setPreferredSize(new Dimension(90,28));}
    static JButton accentButton(String text,Color col){
        JButton b=new JButton(text);b.setBackground(new Color(col.getRed(),col.getGreen(),col.getBlue(),40));
        b.setForeground(col);b.setFont(new Font("SansSerif",Font.BOLD,12));
        b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(col.getRed(),col.getGreen(),col.getBlue(),120)),new EmptyBorder(4,10,4,10)));
        b.setFocusPainted(false);b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addMouseListener(new MouseAdapter(){
            public void mouseEntered(MouseEvent e){b.setBackground(new Color(col.getRed(),col.getGreen(),col.getBlue(),80));}
            public void mouseExited(MouseEvent e){b.setBackground(new Color(col.getRed(),col.getGreen(),col.getBlue(),40));}});return b;}
    static JButton tinyBtn(String text,Color col){JButton b=new JButton(text);
        b.setFont(new Font("SansSerif",Font.PLAIN,11));b.setForeground(col);
        b.setBackground(new Color(col.getRed(),col.getGreen(),col.getBlue(),30));
        b.setBorder(new EmptyBorder(1,4,1,4));b.setFocusPainted(false);b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));return b;}
}
