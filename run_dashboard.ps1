$Env:JAVA_HOME = "C:\Users\Janmejay\.gemini\antigravity\scratch\automate-workflow-test\tools\jdk-21.0.10"
$Env:PATH = "$Env:JAVA_HOME\bin;" + $Env:PATH

$Env:JAVA_HOME = "C:\Users\Janmejay\.gemini\antigravity\scratch\automate-workflow-test\tools\jdk-21.0.10"
$Env:PATH = "$Env:JAVA_HOME\bin;" + $Env:PATH
$Env:MAVEN_HOME = "C:\Users\Janmejay\.gemini\antigravity\scratch\automate-workflow-test\tools\apache-maven-3.9.6"
$Env:PATH = "$Env:MAVEN_HOME\bin;" + $Env:PATH

Write-Host "Resolving classpath and running DashboardBuilder..."
mvn exec:java "-Dexec.mainClass=utils.DashboardBuilder" "-Dexec.classpathScope=test"
Write-Host "Done!"
