// https://github.com/camunda/jenkins-global-shared-library
@Library('camunda-ci') _

String getAgent(String dockerImage = 'gcr.io/ci-30-162810/centos:v0.4.6', Integer cpuLimit = 4){
  String mavenForkCount = cpuLimit;
  String mavenMemoryLimit = cpuLimit * 2;
  """
metadata:
  labels:
    agent: ci-cambpm-camunda-cloud-build
spec:
  nodeSelector:
    cloud.google.com/gke-nodepool: agents-n1-standard-32-netssd-preempt
  tolerations:
  - key: "agents-n1-standard-32-netssd-preempt"
    operator: "Exists"
    effect: "NoSchedule"
  containers:
  - name: "jnlp"
    image: "${dockerImage}"
    args: ['\$(JENKINS_SECRET)', '\$(JENKINS_NAME)']
    tty: true
    env:
    - name: LIMITS_CPU
      value: ${mavenForkCount}
    - name: TZ
      value: Europe/Berlin
    resources:
      limits:
        cpu: ${cpuLimit}
        memory: ${mavenMemoryLimit}Gi
      requests:
        cpu: ${cpuLimit}
        memory: ${mavenMemoryLimit}Gi
    workingDir: "/home/work"
    volumeMounts:
      - mountPath: /home/work
        name: workspace-volume
  """
}

pipeline {
  agent none
  options {
    buildDiscarder(logRotator(numToKeepStr: '5')) //, artifactNumToKeepStr: '30'
    copyArtifactPermission('*');
  }
  parameters {
      string defaultValue: 'pipeline-master', description: 'The name of the EE branch to run the EE pipeline on', name: 'EE_BRANCH_NAME'
  }
  stages {
    stage('First stage') {
      agent {
        kubernetes {
          yaml getAgent('gcr.io/ci-30-162810/centos:v0.4.6', 16)
        }
      }
      steps {
        catchError(stageResult: 'FAILURE') {
          sh "exit 1"
        }
      }
    }
    stage('Second stages') {
      agent {
        kubernetes {
          yaml getAgent('gcr.io/ci-30-162810/centos:v0.4.6', 16)
        }
      }
      parallel {
        stage('Dependent stage') {
          steps {
            sh 'exit 0'
          }
        }
        stage('Independent stage') {
          steps {
            sh 'exit 0'
          }
        }
      }
    }
  }
  post {
    changed {
      script {
        if (!agentDisconnected()){ 
          // send email if the slave disconnected
        }
      }
    }
    always {
      script {
        if (agentDisconnected()) {// Retrigger the build if the slave disconnected
          //currentBuild.result = 'ABORTED'
          //currentBuild.description = "Aborted due to connection error"
          build job: currentBuild.projectName, propagate: false, quietPeriod: 60, wait: false
        }
      }
    }
  }
}

void runMaven(boolean runtimeStash, boolean archivesStash, boolean qaStash, String directory, String cmd, boolean singleThreaded = false) {
  if (runtimeStash) unstash "platform-stash-runtime"
  if (archivesStash) unstash "platform-stash-archives"
  if (qaStash) unstash "platform-stash-qa"
  String forkCount = singleThreaded? "-DforkCount=1" : '';
  configFileProvider([configFile(fileId: 'maven-nexus-settings', variable: 'MAVEN_SETTINGS_XML')]) {
    sh("mvn -s \$MAVEN_SETTINGS_XML ${forkCount} ${cmd} -nsu -Dmaven.repo.local=\${WORKSPACE}/.m2  -f ${directory}/pom.xml -B")
  }
}

void withLabels(String... labels) {
  for ( l in labels) {
    pullRequest.labels.contains(labelName)
  }
}

void withDbLabel(String dbLabel) {
  withLabels(getDbType(dbLabel))
}

String getDbAgent(String dbLabel, Integer cpuLimit = 4, Integer mavenForkCount = 1){
  Map dbInfo = getDbInfo(dbLabel)
  String mavenMemoryLimit = cpuLimit * 4;
  """
metadata:
  labels:
    name: "${dbLabel}"
    jenkins: "slave"
    jenkins/label: "jenkins-slave-${dbInfo.type}"
spec:
  containers:
  - name: "jnlp"
    image: "gcr.io/ci-30-162810/${dbInfo.type}:${dbInfo.version}"
    args: ['\$(JENKINS_SECRET)', '\$(JENKINS_NAME)']
    tty: true
    env:
    - name: LIMITS_CPU
      value: ${mavenForkCount}
    - name: TZ
      value: Europe/Berlin
    resources:
      limits:
        memory: ${mavenMemoryLimit}Gi
      requests:
        cpu: ${cpuLimit}
        memory: ${mavenMemoryLimit}Gi
    volumeMounts:
    - mountPath: "/home/work"
      name: "workspace-volume"
    workingDir: "/home/work"
    nodeSelector:
      cloud.google.com/gke-nodepool: "agents-n1-standard-4-netssd-preempt"
    restartPolicy: "Never"
    tolerations:
    - effect: "NoSchedule"
      key: "agents-n1-standard-4-netssd-preempt"
      operator: "Exists"
    volumes:
    - emptyDir:
        medium: ""
      name: "workspace-volume"
  """
}

Map getDbInfo(String databaseLabel) {
  Map SUPPORTED_DBS = ['postgresql_96': [
                           type: 'postgresql',
                           version: '9.6v0.2.2',
                           profiles: 'postgresql',
                           extra: ''],
                       'mariadb_103': [
                           type: 'mariadb',
                           version: '10.3v0.3.2',
                           profiles: 'mariadb',
                           extra: ''],
                       'sqlserver_2017': [
                           type: 'mssql',
                           version: '2017v0.1.1',
                           profiles: 'sqlserver',
                           extra: '-Ddatabase.name=camunda -Ddatabase.username=sa -Ddatabase.password=cam_123$']
  ]

  return SUPPORTED_DBS[databaseLabel]
}

String getDbType(String dbLabel) {
  String[] database = dbLabel.split("_")
  return database[0]
}

String getDbProfiles(String dbLabel) {
  return getDbInfo(dbLabel).profiles
}

String getDbExtras(String dbLabel) {
  return getDbInfo(dbLabel).extra
}

String resolveMavenProfileInfo(String profile) {
  Map PROFILE_PATHS = [
      'engine-unit': [
          directory: 'engine/',
          command: 'clean test -P'],
      'engine-unit-authorizations': [
          directory: 'engine/',
          command: 'clean test -PcfgAuthorizationCheckRevokesAlways,'],
      'webapps-unit': [
          directory: 'webapps/',
          command: 'clean test -Dskip.frontend.build=true -P'],
      'webapps-unit-authorizations': [
          directory: 'webapps/',
          command: 'clean test -Dskip.frontend.build=true -PcfgAuthorizationCheckRevokesAlways,']
  ]

  return PROFILE_PATHS[profile]
}

String getMavenProfileCmd(String profile) {
  return resolveMavenProfileInfo(profile).command
}

String getMavenProfileDir(String profile) {
  return resolveMavenProfileInfo(profile).directory
}