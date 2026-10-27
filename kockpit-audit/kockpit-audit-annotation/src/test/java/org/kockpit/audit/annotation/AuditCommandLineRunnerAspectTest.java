package org.kockpit.audit.annotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

class AuditCommandLineRunnerAspectTest {

  RecordingAspect aspect;

  @BeforeEach
  void setUp() {
    aspect = new RecordingAspect(mock(Environment.class), null);
  }

  @Test
  void shouldExitWithSuccessCodeWhenAnnotatedMethodReturns() throws Exception {
    proxy(new MethodAnnotatedRunner(false)).run();

    assertThat(aspect.exitCodes).containsExactly(0);
  }

  @Test
  void shouldExitWithFailureCodeWhenAnnotatedMethodThrows() {
    CommandLineRunner runner = proxy(new MethodAnnotatedRunner(true));

    assertThatThrownBy(runner::run).isInstanceOf(IllegalStateException.class);
    assertThat(aspect.exitCodes).containsExactly(1);
  }

  @Test
  void shouldApplyToRunMethodWhenClassIsAnnotated() throws Exception {
    proxy(new ClassAnnotatedRunner(false)).run();

    assertThat(aspect.exitCodes).containsExactly(0);
  }

  @Test
  void shouldExitWithFailureCodeWhenAnnotatedClassRunThrows() {
    CommandLineRunner runner = proxy(new ClassAnnotatedRunner(true));

    assertThatThrownBy(runner::run).isInstanceOf(IllegalStateException.class);
    assertThat(aspect.exitCodes).containsExactly(1);
  }

  @Test
  void shouldNotApplyToOtherMethodsWhenClassIsAnnotated() {
    AspectJProxyFactory factory = new AspectJProxyFactory(new ClassAnnotatedRunner(false));
    factory.setProxyTargetClass(true);
    factory.addAspect(aspect);
    ClassAnnotatedRunner runner = factory.getProxy();

    runner.helper();

    assertThat(aspect.exitCodes).isEmpty();
  }

  @Test
  void shouldNotApplyToUnannotatedRunner() throws Exception {
    proxy(args -> {}).run();

    assertThat(aspect.exitCodes).isEmpty();
  }

  @Test
  void shouldPropagateFailureCodeThroughApplicationContext() {
    aspect =
        new RecordingAspect(mock(Environment.class), mock(ConfigurableApplicationContext.class));
    CommandLineRunner runner = proxy(new MethodAnnotatedRunner(true));

    assertThatThrownBy(runner::run).isInstanceOf(IllegalStateException.class);
    assertThat(aspect.exitCodes).containsExactly(1);
  }

  private CommandLineRunner proxy(CommandLineRunner target) {
    AspectJProxyFactory factory = new AspectJProxyFactory(target);
    factory.addAspect(aspect);
    return factory.getProxy();
  }

  static class RecordingAspect extends AuditCommandLineRunnerAspect {

    final List<Integer> exitCodes = new ArrayList<>();

    RecordingAspect(Environment environment, ConfigurableApplicationContext context) {
      super(environment, context);
    }

    @Override
    protected void exit(int status) {
      exitCodes.add(status);
    }
  }

  static class MethodAnnotatedRunner implements CommandLineRunner {

    private final boolean fail;

    MethodAnnotatedRunner(boolean fail) {
      this.fail = fail;
    }

    @Override
    @AuditCommandLineRunner
    public void run(String... args) {
      if (fail) {
        throw new IllegalStateException("job failed");
      }
    }
  }

  @AuditCommandLineRunner
  static class ClassAnnotatedRunner implements CommandLineRunner {

    private final boolean fail;

    ClassAnnotatedRunner(boolean fail) {
      this.fail = fail;
    }

    @Override
    public void run(String... args) {
      if (fail) {
        throw new IllegalStateException("job failed");
      }
    }

    public void helper() {}
  }
}
