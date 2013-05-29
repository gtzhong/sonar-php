/*
 * Sonar PHP Plugin
 * Copyright (C) 2010 Codehaus Sonar Plugins
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.plugins.php.phpdepend;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.XStreamException;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.Measure;
import org.sonar.api.measures.PersistenceMode;
import org.sonar.api.measures.RangeDistributionBuilder;
import org.sonar.api.resources.File;
import org.sonar.api.resources.Project;
import org.sonar.api.resources.ResourceUtils;
import org.sonar.api.utils.SonarException;
import org.sonar.plugins.php.phpdepend.summaryxml.ClassNode;
import org.sonar.plugins.php.phpdepend.summaryxml.FileNode;
import org.sonar.plugins.php.phpdepend.summaryxml.FunctionNode;
import org.sonar.plugins.php.phpdepend.summaryxml.MethodNode;
import org.sonar.plugins.php.phpdepend.summaryxml.MetricsNode;
import org.sonar.plugins.php.phpdepend.summaryxml.PackageNode;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * This parser is responsible for parsing summary-xml report generated by Php Depend
 * and saving software metrics found inside
 *
 * @since 1.1
 */
public class PhpDependSummaryReportParser extends PhpDependResultsParser {

  /**
   * Instantiates a new php depend results parser.
   *
   * @param project the project
   * @param context the context
   */
  public PhpDependSummaryReportParser(Project project, SensorContext context) {
    super(project, context);
  }

  @Override
  public void parse(java.io.File reportXml) {
    if (!reportXml.exists()) {
      throw new SonarException("PDepdend result file not found: " + reportXml.getAbsolutePath() + ".");
    }

    MetricsNode metricsNode = getMetrics(reportXml);
    metricsNode.findMatchingMetrics();

    for (FileNode fileNode : metricsNode.getFiles()) {

      File sonarFile = validProjectFile(fileNode);
      if (sonarFile == null) {
        continue;
      }

      RangeDistributionBuilder classComplexityDistribution = new RangeDistributionBuilder(
          CoreMetrics.CLASS_COMPLEXITY_DISTRIBUTION,
          CLASSES_DISTRIB_BOTTOM_LIMITS);
      RangeDistributionBuilder methodComplexityDistribution = new RangeDistributionBuilder(
          CoreMetrics.FUNCTION_COMPLEXITY_DISTRIBUTION,
          FUNCTIONS_DISTRIB_BOTTOM_LIMITS);

      getContext().saveMeasure(sonarFile, CoreMetrics.CLASSES, (double) fileNode.getClassNumber());
      getContext().saveMeasure(sonarFile, CoreMetrics.FUNCTIONS, (double) fileNode.getFunctionNumber() + fileNode.getMethodNumber());

      List<ClassNode> classes = fileNode.getClasses();

      if (classes != null) {
        boolean firstClass = true;

        for (ClassNode classNode : classes) {
          if (firstClass) {
            // we save the DIT, NumberOfChildren and complexity only for the first class,
            // as usually there will be only 1 class per file
            // FIX: Which is not a reason to do so!
            getContext().saveMeasure(
                sonarFile,
                CoreMetrics.DEPTH_IN_TREE,
                classNode.getDepthInTreeNumber()
                );
            getContext().saveMeasure(
                sonarFile,
                CoreMetrics.NUMBER_OF_CHILDREN,
                classNode.getNumberOfChildrenClasses()
                );

            double totalClassComplexity = classNode.getWeightedMethodCount();
            getContext().saveMeasure(sonarFile, CoreMetrics.COMPLEXITY, totalClassComplexity);
            classComplexityDistribution.add(totalClassComplexity);

            firstClass = false;
          }

          updateMethodComplexityDistribution(classNode, methodComplexityDistribution);
        }

        updateMethodComplexityDistribution(fileNode, methodComplexityDistribution);
      }

      Measure measure = classComplexityDistribution.build().setPersistenceMode(PersistenceMode.MEMORY);
      getContext().saveMeasure(
          sonarFile,
          measure
          );
      getContext().saveMeasure(
          sonarFile,
          methodComplexityDistribution.build().setPersistenceMode(PersistenceMode.MEMORY)
          );
    }
  }

  private void updateMethodComplexityDistribution(FileNode fileNode, RangeDistributionBuilder methodComplexityDistribution) {
    List<FunctionNode> functions = fileNode.getFunctions();
    if (functions != null) {
      for (FunctionNode functionNode : functions) {
        methodComplexityDistribution.add(functionNode.getComplexity());
      }
    }
  }

  private void updateMethodComplexityDistribution(ClassNode classNode, RangeDistributionBuilder methodComplexityDistribution) {
    List<MethodNode> methods = classNode.getMethods();
    if (methods != null) {
      for (MethodNode methodNode : methods) {
        methodComplexityDistribution.add(methodNode.getComplexity());
      }
    }
  }

  private File validProjectFile(FileNode fileNode) {
    String fileName = fileNode.getFileName();
    if (StringUtils.isEmpty(fileName)) {
      return null;
    }

    File sonarFile = File.fromIOFile(new java.io.File(fileName), getProject());
    if (sonarFile != null && !ResourceUtils.isUnitTestClass(sonarFile)) {
      return sonarFile;
    } else {
      return null;
    }
  }

  /**
   * Gets the metrics.
   *
   * @param report
   *          the report
   * @return the metrics
   */
  private MetricsNode getMetrics(java.io.File report) {
    InputStream inputStream = null;
    try {
      XStream xstream = new XStream();
      // Migration Sonar 2.2
      xstream.setClassLoader(getClass().getClassLoader());
      xstream.processAnnotations(MetricsNode.class);
      xstream.processAnnotations(PackageNode.class);
      xstream.processAnnotations(ClassNode.class);
      xstream.processAnnotations(FileNode.class);
      xstream.processAnnotations(MethodNode.class);
      xstream.processAnnotations(FunctionNode.class);
      inputStream = new FileInputStream(report);
      return (MetricsNode) xstream.fromXML(inputStream);
    } catch (XStreamException e) {
      throw new SonarException("PDepend report isn't valid: " + report.getName() + ". Details: " + e.getMessage(), e);
    } catch (IOException e) {
      throw new SonarException("Can't read report : " + report.getName() + ". Details: " + e.getMessage(), e);
    } finally {
      IOUtils.closeQuietly(inputStream);
    }
  }
}
