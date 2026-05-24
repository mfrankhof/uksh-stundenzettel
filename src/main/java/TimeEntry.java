import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;

record TimeEntry(LocalDate date, LocalTime start, LocalTime end, Duration breakDuration, Duration total,
                 String remark) {
}
