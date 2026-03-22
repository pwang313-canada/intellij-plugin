package org.cakk.memoryleak.startup;

import org.cakk.memoryleak.core.MemoryLeakMonitorService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class MemoryLeakStartupActivity implements ProjectActivity {

  @Nullable
  @Override
  public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
    // Initialize the service
    project.getService(MemoryLeakMonitorService.class);
    return Unit.INSTANCE;
  }
}