package eval2;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.annotations.CommandLineArguments;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

@ApplicationScoped
public class Eval {

    private static final Logger log = Logger.getLogger(Eval.class);

    private static final String PROJECTS_DIR = System.getProperty("user.dir") + "/" + "projects";
    private static final File PROJECTS_FOLDER = new File(PROJECTS_DIR);

    private static final List<String> evaluationDates = new ArrayList<>();

    private static Date fromDate;
    private static Date toDate;

    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

    @Inject
    @CommandLineArguments
    String[] args;

    void onStart(@Observes StartupEvent ev) {
        log.info("eval-service: start");
    }


    @Scheduled(every = "${eval.interval}")
    void run() {
        log.info("eval-service: run");

        if (args.length == 0) {
            evaluationDates.add(dateFormat.format(new Date()));
        }

        if (args.length == 2) {
            if (args[0].equals("evaluationDate")) {
                try {
                    dateFormat.parse(args[1]);
                    evaluationDates.add(args[1]);
                    log.info("Using user-defined evaluationDate: " + args[1]);
                } catch (ParseException e) {
                    usage();
                    return;
                }
            } else {
                usage();
                return;
            }
        }

        if (args.length == 4) {
            if (args[0].equals("from") && args[2].equals("to")) {
                try {
                    fromDate = dateFormat.parse(args[1]);
                    toDate = dateFormat.parse(args[3]);

                    evaluationDates.addAll(enumeratePeriod(fromDate, toDate));

                    log.info("Using user-defined evaluation period: " + dateFormat.format(fromDate) + " - " + dateFormat.format(toDate) + ".\n");
                } catch (ParseException e) {
                    usage();
                    return;
                }
            } else {
                usage();
                return;
            }
        }

        List<File> projectFolders = getProjectFolders(PROJECTS_DIR);

        evaluate(projectFolders);
    }

    private static void evaluate(List<File> projectFolders) {
        for (File projectDir : projectFolders) {
            for (String ed : evaluationDates) {
                log.info("Evaluating project folder " + projectDir.getName() + " for evaluationDate " + ed + ".\n");
                try {
                    EvalProject ep = new EvalProject(projectDir, ed);
                    ep.run();
                } catch (Exception e) {
                    e.printStackTrace();
                    log.error("Evaluaton of project in folder " + projectDir + " terminated with an error!");
                }
            }
        }
    }

    public static void evaluateQualityModel(String dir, Date date1, Date date2) throws ParseException {

        // read parameters
        if (date1 != null && date2 != null) { // if two dates are passed --> we obtain a from and to dates
            fromDate = date1;
            toDate = date2;
            evaluationDates.addAll(enumeratePeriod(fromDate, toDate));
            log.info("Using user-defined evaluation period: " + dateFormat.format(fromDate) + " - " + dateFormat.format(toDate) + ".\n");
        } else if (date1 != null) { // if only one date is passed --> we obtain an evaluation date
            evaluationDates.add(dateFormat.format(date1));
            log.info("Using user-defined evaluationDate: " + dateFormat.format(date1));
        }

        List<File> projectFolders = getProjectFolders(dir);

        evaluate(projectFolders);
    }

    private static List<String> enumeratePeriod(Date fromDate, Date toDate) {

        Calendar c = Calendar.getInstance();

        List<String> days = new ArrayList<>();

        Date i = fromDate;
        while (i.compareTo(toDate) <= 0) {
            days.add(dateFormat.format(i));
            c.setTime(i);
            c.add(Calendar.DATE, 1);
            i = c.getTime();
        }

        return days;
    }

    private static void usage() {
        System.out.println("Usage:");
        System.out.println("java -jar qrapids-eval.jar");
        System.out.println("java -jar qrapids-eval.jar evaluationDate 2019-01-31");
        System.out.println("java -jar qrapids-eval.jar from 2019-01-01 to 2019-01-31");
    }

    private static List<File> getProjectFolders(String dir) {
        List<File> result = new ArrayList<>();
        File[] folder = PROJECTS_FOLDER.listFiles(); // by default use defined projects folder

        if (dir != null && !dir.isEmpty())
            folder = new File(dir).listFiles(); // if dir passed use it as projects folder

        for (File p : folder) {
            if (p.isDirectory()) {
                result.add(p);
            }
        }

        return result;
    }

}
