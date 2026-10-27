package org.kockpit.audit.annotation;

import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

@Aspect
@Slf4j
public class AuditCommandLineRunnerAspect {

  static final int SUCCESS_EXIT_CODE = 0;
  static final int FAILURE_EXIT_CODE = 1;

  private final Environment environment;
  private final ConfigurableApplicationContext context;

  public AuditCommandLineRunnerAspect(
      Environment environment, ConfigurableApplicationContext context) {
    this.environment = environment;
    this.context = context;
  }

  @Pointcut("@annotation(org.kockpit.audit.annotation.AuditCommandLineRunner)")
  void annotatedMethod() {}

  // Class-level usage only applies to the runner entry point, not to every method of the class
  @Pointcut(
      "@within(org.kockpit.audit.annotation.AuditCommandLineRunner)"
          + " && execution(* org.springframework.boot.CommandLineRunner+.run(..))")
  void annotatedRunner() {}

  @Before("annotatedMethod() || annotatedRunner()")
  public void before() {
    logApplicationStartup(environment);
  }

  @AfterReturning("annotatedMethod() || annotatedRunner()")
  public void afterReturning() {
    logApplicationFinish(environment);
    forceStopApp(SUCCESS_EXIT_CODE);
  }

  @AfterThrowing(pointcut = "annotatedMethod() || annotatedRunner()", throwing = "ex")
  public void afterThrowing(Throwable ex) {
    log.error(
        "Application '{}' failed", environment.getProperty("spring.application.name"), ex);
    logApplicationFinish(environment);
    forceStopApp(FAILURE_EXIT_CODE);
  }

  protected static void logApplicationStartup(Environment env) {
    log.info(
        "\n----------------------------------------------------------\n\tApplication '{}' is running!\n\tProfile(s): \t{}\n----------------------------------------------------------",
        env.getProperty("spring.application.name"),
        env.getActiveProfiles());
  }

  protected static void logApplicationFinish(Environment env) {
    log.info(
        "\n----------------------------------------------------------\n\tApplication '{}' terminated!\n----------------------------------------------------------",
        env.getProperty("spring.application.name"));
  }

  protected void forceStopApp(int exitCode) {
    // Used since aws scheduled task is stuck in running state
    if (Objects.isNull(context)) {
      exit(exitCode);
    } else {
      exit(SpringApplication.exit(context, () -> exitCode));
    }
  }

  protected void exit(int status) {
    System.exit(status);
  }
}
