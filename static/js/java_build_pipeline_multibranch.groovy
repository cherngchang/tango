import com.mufg.tsi.devsecops.pipelines.Helper

class BuildConfiguration {
    def id = ""
    def baseImage = ""
    def buildOption = ""
    def packagingType = ""
}

def call(
        String openshiftCluster = "",
        String openshiftProject = "",
        String dockerRegistryURL = "",
        String baseImageJava = "openjdk/openjdk-11-rhel7:1.1-4",
        String baseImageTomcat = "jboss-webserver-5/webserver52-openjdk11-tomcat9-openshift-rhel7:1.0-5.1575996526",
        String baseImageJboss = "jboss-eap-7/eap72-openjdk11-openshift-rhel8:1.2-5"
) {

    assert openshiftCluster?.trim(): "openshift Cluster is required."
    assert openshiftProject?.trim(): "openshift Project is required."
    assert dockerRegistryURL?.trim(): "dockerRegistryURL is required."
    assert env.OPENSHIFT_APP_PROJECTS?.trim(): "Env OPENSHIFT_APP_PROJECTS is required"

    def DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX = /release.*|hotfix.*|develop|master/
    def SECURITY_QUALITY_PASS_FLAG = true
    def LOG_LEVEL = 2
    def POM_RELEASE = env.BRANCH_NAME
    def POM = ''
    def USER_DOC = 'https://prod-1-confluence.mufgamericas.com/display/CICDDocs/CICD+Pipelines+User+Documentation'
    def pomQualityCheckOverrideProperties = [
            'cpd.skip',
            'checkstyle.skip',
            'pmd.skip',
            'skip.pmd.check',
            'maven.test.skip',
    ]

    BuildConfiguration[] buildConfigurations = [
            new BuildConfiguration(id: "java", baseImage: env.BASE_IMAGE_JAVA ?: baseImageJava, buildOption: env.BUILD_OPTION_JAVA ?: "", packagingType: "jar|pom"),
            new BuildConfiguration(id: "tomcat", baseImage: env.BASE_IMAGE_TOMCAT ?: baseImageTomcat, buildOption: env.BUILD_OPTION_TOMCAT ?: "-Ddocker.containerizing.mode=exploded", packagingType: "war|pom"),
            new BuildConfiguration(id: "jboss", baseImage: env.BASE_IMAGE_JBOSS ?: baseImageJboss, buildOption: env.BUILD_OPTION_JBOSS ?: "-Djib.container.appRoot=/opt/eap/standalone/deployments", packagingType: ".*")
    ] as BuildConfiguration[]
    //This variable used for  setting parameters "No Deployment" choice and verify user choose "No Deployment"
    NO_DEPLOYMENT = 'No Deployment'
    CLUSTER_CONFIG_FILE = 'com/mufg/tsi/devsecops/pipelines/openshift.yaml'
    //This flag is used to carry deployment status true: deploy, false: no deploy
    DEPLOYMENT_FLAG = true
    VERACODE_SCRIPTS = 'com/mufg/tsi/devsecops/pipelines/bin/vcode_helper.sh'
    EMERGENCY_CM_APPROVERS_FILE = 'change_management/emergency_approvers.yaml'
    CUCUMBER_REPORT = 1

    final Map<String, Map<String, String>> CLUSTER_MAP = new HashMap<>()

    pipeline {
        agent {
            kubernetes {
                cloud "${openshiftCluster}-${openshiftProject}"
                label "jenkins-agent-pod-multibranch-${openshiftProject}-${BUILD_NUMBER}"
                yaml libraryResource("agent-pod-conf/jenkins-agent-pod-multibranch.yaml")
            }
        }
        options {
            //GLOBAL BUILD OPTIONS
            buildDiscarder(logRotator(numToKeepStr: '13'))
            disableConcurrentBuilds()
            timeout(time: 180, unit: 'MINUTES')
        }

        environment {
            NEXUS_REGISTRY_URL = "${dockerRegistryURL}"
            // This path is from a emptydir volume primarily for  multiple containers to share files
            MAVEN_ARTIFACTS = '/var/tmp/maven-artifacts'

            // These are for oc to make outbound connection to the OpenShift cluster URL
            PROXY = 'http://ub-app-proxy.uboc.com:80'
            HTTP_PROXY = "${env.PROXY}"
            HTTPS_PROXY = "${env.PROXY}"
            // This is a hack to force connectivity to these cluster to go direct
            NO_PROXY = "opn.com,opn-console-mmz-uat2.opn.com,opn.uk.com"
        }

        parameters {
            choice(name: "Deployment", choices: "${fetchClustersFromOnboardingFile(CLUSTER_MAP)}", description: "Choose the environment to deploy.")
            string(name: 'ChangeNumber', defaultValue: '', description: 'Change Management Ticket Number - e.g. CHG1234567. This must be provided for production deployment or the deployment will be denied. For emergency deployment, i.e. in case an emergency change management tickat can\'t be obtained, enter \"emergency\" and reach out to the pipeline operations team for approval.')
            string(name: 'CucumberTags', defaultValue: '', description: 'Cucumber tags for testing such as @SIT,@CIF separated with comma')
            string(name: 'testsToRun', defaultValue: '', description: 'ALM Octane Test Parameters')
        }

        stages {
            stage("parameterizing") {
                steps {
                    script {
                        if ("${params.Invoke_Parameters}" == "Yes") {
                            currentBuild.result = 'ABORTED'
                            error('DRY RUN COMPLETED. JOB PARAMETERIZED.')
                        }
                        // determine deployment is required when parameterizing
                        //set DEPLOYMENT_FLAG = false when deployment should be stoped
                        if ((!(env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) && !env.TAG_NAME)) {
                            DEPLOYMENT_FLAG = false
                            echo "No Deployment:  Branch chosen ${env.BRANCH_NAME} is not in defined the allowed list ${DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX} or TAG_NAME is not defined ${env.TAG_NAME}"
                        }

                        if ("${NO_DEPLOYMENT}" == "${params.Deployment}" || "${params.Deployment}" == "") {
                            DEPLOYMENT_FLAG = false
                            echo "No Deployment build was chosen"
                        }

                        if (!env.K8S_DEPLOYMENT_CONFIG || env.K8S_DEPLOYMENT_CONFIG?.trim() == "" || !env.K8S_DEPLOYMENT_REPOSITORY || env.K8S_DEPLOYMENT_REPOSITORY?.trim() == "") {
                            DEPLOYMENT_FLAG = false
                            echo "No Deployment configuration"
                        }
                    }
                }
            }
            stage('Validate POM File') {
                steps {
                    container('maven') {
                        script {
                            POM = readMavenPom file: 'pom.xml'
                            assert POM.properties['gsi-code']: "Invalid pom.xml - See details at ${USER_DOC}"
                            def pomVersion = POM.version
                            if (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) {
                                if (pomVersion.startsWith('${revision}')) {
                                    pomVersion = POM.properties['revision']
                                }
                                echo "Release pom version: ${pomVersion}"
                            } else {
                                echo "Non release pom version: ${POM.version} ${env.BRANCH_NAME}"
                                if (env.TAG_NAME) {
                                    pomVersion = env.BRANCH_NAME
                                }
                            }
                            env.POM_VERSION = pomVersion.replaceAll("-SNAPSHOT", "") + "${escapeBranch()}"

                            echo "pom version original: ${POM.version} "
                            echo "branch ${escapeBranch()}"
                            echo "Build Number: ${env.BUILD_NUMBER}"

                            try {
                                for (property in pomQualityCheckOverrideProperties) {
                                    overrideValue = POM.properties[property];
                                    if (overrideValue != null) {
                                        echo "${property}: ${overrideValue}";
                                        assert !overrideValue.toBoolean();
                                    }
                                }
                                assert POM.properties['maven.pmd.maxAllowedViolations'] == null
                            } catch (AssertionError e) {
                                echo "Invalid POM Detected - Evil No Bad – Don’t turn off quality checks, this is against Bank Procedures - See details at ${USER_DOC}"
                                currentBuild.result = 'FAILURE'
                                throw e
                            }
                        }
                        echo "POM_VERSION: ${env.POM_VERSION}"
                    }
                }
            }

            stage('Build Binaries') {
                when {
                    not { buildingTag() }
                }
                steps {
                    script {
                        container('maven') {
                            def cucumberTag = "${params.CucumberTags}" == "" ? '' : "-Dcucumber.options=\"--tags ${params.CucumberTags}\""
                            def testsToRun = params.testsToRun ?: ''
                            def buildCommand = "mvn clean install ${cucumberTag}"
                            if (testsToRun != "") {
                                echo "test to run: ${testsToRun}"
                                convertTestsToRun format: '', framework: 'cucumber_jvm'
                                buildCommand = buildCommand + "-Dfeatures=\"$testsToRunConverted\""
                            }
                            sh buildCommand
                            CUCUMBER_REPORT = sh(script: "find ${WORKSPACE} -name *cucumber*.json | grep .", returnStatus: true)
                        }
                    }
                }
            }

            stage('Nexus IQ Scan') {
                when {
                    not { buildingTag() }
                }
                steps {
                    container('maven') {
                        script {
                            catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE', SECURITY_QUALITY_PASS_FLAG: (SECURITY_QUALITY_PASS_FLAG & false)) {
                                nexusIQScan(
                                        IQ_STAGE: (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) ? (("${env.BRANCH_NAME}" == "develop") ? 'build' : 'release') : 'build',
                                        GSI: POM.properties['gsi-code'],
                                        ARTIFACT_ID: POM.artifactId
                                )
                            }
                        }
                    }
                }
            }

            stage('Veracode Scan') {
                when {
                    not { buildingTag() }
                }
                steps {
                    container('maven') {
                        script {
                            catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE', SECURITY_QUALITY_PASS_FLAG: (SECURITY_QUALITY_PASS_FLAG & false)) {
                                veracodeScan(
                                        buildType: (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) ? (("${env.BRANCH_NAME}" == "develop") ? 'snapshot' : 'release') : 'snapshot',
                                        gsiCode: POM.properties['gsi-code']
                                )
                            }
                        }
                    }
                }
            }

            stage('Publish Artifacts') {
                when {
                    allOf {
                        expression { SECURITY_QUALITY_PASS_FLAG }
                        not { buildingTag() }
                    }
                }
                environment {
                    GIT_AUTH = credentials('bitbucket-tagging')
                }
                steps {
                    script {
                        container('maven') {
                            if (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) {
                                try {
                                    sh "mvn versions:set -DnewVersion=${env.POM_VERSION}"

                                    POM_RELEASE = "v${env.POM_VERSION}"
                                    //Assuming the parent pom is always enterprise-tomcat/java/jboss-pom with buildType between - and -
                                    def buildTypes
                                    if (POM.parent != null && POM.parent.artifactId != null) {
                                        buildTypes = "${POM.parent.artifactId}".split('-')
                                    } else {
                                        buildTypes = []
                                    }
                                    def buildConfiguration = buildConfigurations.find { it.id == (buildTypes.size() > 1 ? buildTypes[1] : "java") } ?: buildConfigurations[0]
                                    assert (POM.packaging =~ /${buildConfiguration.packagingType}/): "Project packaging mismatches base image. Jar for spring boot, war for tomcat and EAP for any. ${buildConfiguration.packagingType}"
                                    def buildCommandLine = "${buildConfiguration.buildOption} -Ddocker.image.tag=${POM_RELEASE} -Dbase.image=${buildConfiguration.baseImage}"
                                    def buildProfile = env.BUILD_PROFILE ?: 'docker'

                                    echo "Build Command Line: ${buildCommandLine}"
                                    echo "Build Profile: ${buildProfile}"

                                    //skip testing in deployment phase

                                    sh "mvn deploy -P ${buildProfile} ${buildCommandLine} -DskipTests=true"

                                    //Tagging
                                    //String NEXUS = "${dockerRegistryURL}".split(':')[0]
                                    //env.NEXUS_URL = "https://${NEXUS}"
                                    //String deploymentName = params.Deployment
                                    //Map<String, String> clusterEnv = CLUSTER_MAP.get(deploymentName.trim())
                                    //String environment = clusterEnv.get("environment")
                                    //tagAssociateAPI(environment, env.POM_VERSION, POM.artifactId)

                                    sh "git tag ${POM_RELEASE} -am \"Jenkins release tag\""
                                    sh('''
                                    git config --local credential.helper "!f() { echo username=\\$GIT_AUTH_USR; echo password=\\$GIT_AUTH_PSW; }; f"
                                    git push origin --tags
                                ''')
                                } catch (error) {
                                    echo "Exception in deploy: " + error.getMessage()
                                    throw error
                                }
                            } else {
                                echo "Code is not in a deploy branch. Bypassing deployment build."
                            }
                        }
                    }
                }
            }

            stage('Change Management Validation') {
                when {
                    allOf {
                        expression { SECURITY_QUALITY_PASS_FLAG }
                    }
                }
                steps {
                    container('python3') {
                        script {
                            if ((!(env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) && !env.TAG_NAME)
                                    || "${NO_DEPLOYMENT}" == "${params.Deployment}"
                                    || "${params.Deployment}" == "") {
                                echo "No deployment."
                            } else {
                                echo "Deplolyment cluster: ${params.Deployment}"
                                String deploymentName = params.Deployment
                                Map<String, String> clusterEnv = CLUSTER_MAP.get(deploymentName.trim())
                                String environment = clusterEnv.get("environment")
                                String clusterURL = getEnvUrl(clusterEnv.get("cluster"))
                                String project = clusterEnv.get("project")
                                String cluster = clusterEnv.get("cluster")

                                if (Helper.isCMRequired(environment)) {
                                    // if ticket number = emergency, allow for emergency deployment by the pipeline operations team
                                    if (params.ChangeNumber.trim().toLowerCase() == "emergency") {
                                        echo "########## IMPORTANT ##########"
                                        echo "This is an emergency deployment to Production."
                                        def userInput = ''
                                        timeout(time: 15, unit: "MINUTES") {
                                            userInput = input(message: 'Pipeline Operator: Do you approve this emergency deployment to Production?',
                                                    ok: 'Approve',
                                                    submitter: getEmergencyCMApprovers(),
                                                    parameters: [
                                                            [$class      : 'TextParameterDefinition',
                                                             defaultValue: '',
                                                             description : 'Enter "Approved" literally to approve or the deployment will be aborted.',
                                                             name        : '']
                                                    ])
                                        }
                                        if (userInput.trim().toLowerCase() != "approved") {
                                            error "Invalid value for approving emergency deployment. Deployment aborted."
                                        }
                                        echo "########## IMPORTANT ##########"
                                        echo "Emergency deployment approved. Proceed to deployment."
                                    } else {
                                        echo "This is a Production Deployment. Validate Change Ticket with CM (ServiceNow)."
                                        try {
                                            validateChangeManagementTicket(
                                                    gsiCode: "${env.GSI}",
                                                    changeNumber: "${params.ChangeNumber}"
                                            )
                                            //TO-DO - validate cluster/project with labels matching environment
                                        } catch (e) {
                                            //currentBuild.result = "FAILURE"
                                            error "Change Management Validation Failed. See logs for more details.\n${e}"
                                        }
                                    }
                                } else {
                                    echo "Change Management is not required for ${environment} environment."
                                }
                            }
                        }
                    }
                }
            }

            stage('Deploy to Target Environment') {
                when {
                    allOf {
                        expression { SECURITY_QUALITY_PASS_FLAG }
                        expression { DEPLOYMENT_FLAG }
                    }
                }
                steps {
                    container('jnlp') {
                        script {
                            if ((!(env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) && !env.TAG_NAME)
                                    || "${NO_DEPLOYMENT}" == "${params.Deployment}"
                                    || "${params.Deployment}" == "") {
                                echo "No deployment."
                            } else {
                                echo "Deplolyment clusters: ${params.Deployment}"
                                if (env.K8S_DEPLOYMENT_REPOSITORY) {
                                    //assuming deployment repo has the same branch name as environment name except prd for master
                                    assert env.K8S_DEPLOYMENT_CONFIG: "K8S_DEPLOYMENT_CONFIG is required to deploy. Assuming the IMAGE_VERSION will be replaced with current build version"
                                    String deploymentName = params.Deployment
                                    Map<String, String> clusterEnv = CLUSTER_MAP.get(deploymentName.trim())
                                    String branchName = clusterEnv.get("environment")
                                    branchName = "prd".equalsIgnoreCase(branchName) ? "master" : branchName
                                    dir('deployment-repo') {
                                        git branch: branchName, url: env.K8S_DEPLOYMENT_REPOSITORY, credentialsId: 'bitbucket'
                                        try {
                                            String clusterURL = getEnvUrl(clusterEnv.get("cluster"))
                                            String project = clusterEnv.get("project")
                                            String cluster = clusterEnv.get("cluster")
                                            String tokenId = cluster + '-' + project + '-jenkins'
                                            String imageTagName = env.IMAGE_VERSION_TAG ?: 'IMAGE_VERSION'

                                            echo "Deployment Name: ${deploymentName}"
                                            echo "clusterEnv: ${clusterEnv}"
                                            echo "Cluster Map: ${CLUSTER_MAP}"
                                            echo "Cluster URL: ${clusterURL}"
                                            echo "TOKEN ID: ${tokenId}"
                                            echo "image version tag: ${imageTagName}"

                                            sh "find . -type f -exec sed -i s/${imageTagName}/${POM_RELEASE}/g {} \\;"
                                            withCredentials([string(credentialsId: "${tokenId}", variable: 'TOKEN')]) {
                                                openshift.logLevel(LOG_LEVEL)
                                                applyFile(
                                                        projectName: project,
                                                        deploymentFile: "${env.K8S_DEPLOYMENT_CONFIG}",
                                                        clusterAPI: clusterURL,
                                                        clusterToken: "${TOKEN}"
                                                )
                                            }

                                            //Tagging
                                            //String NEXUS = "${dockerRegistryURL}".split(':')[0]
                                            //env.NEXUS_URL = "https://${NEXUS}"
                                            //String environment = clusterEnv.get("environment")
                                            //tagAssociateAPI(environment, env.POM_VERSION, POM.artifactId)

                                            if (Helper.isCMRequired(clusterEnv.get("environment"))) {
                                                // Preserve build for successful Production deployment
                                                currentBuild.keepLog = true
                                            }
                                        } catch (e) {
                                            echo "Error encountered: " + e.getMessage()
                                            throw e
                                        }
                                    }
                                } else {
                                    echo "No deployment repo is specified! No deployment."
                                }
                            }
                        }
                    }
                }
            }
        }
        post {
            always {
                script {
                    echo "cucumber report ${CUCUMBER_REPORT}"
                    if (CUCUMBER_REPORT == 0) {
                        cucumber '**/*cucumber*.json'
                        publishGherkinResults ''
                    }
                }
            }
        }
    }
}

def escapeBranch() {
    if (env.BRANCH_NAME =~ /release.*|develop|master/) {
        return "." + "${env.BUILD_NUMBER}"
    }
    return "-" + "${env.BRANCH_NAME}".replace('/', '-') + "." + "${env.BUILD_NUMBER}"
}

def fetchClustersFromOnboardingFile(Map<String, Map<String, String>> clustersMap) {
    List<Map<String, String>> clustersData = Helper.base64DecodeMap(env.OPENSHIFT_APP_PROJECTS)
    echo "OPENSHIFT_APP_PROJECTS: ${clustersData}"
    StringBuffer clusterNames = new StringBuffer()
    for (Map<String, String> map : clustersData) {
        StringBuffer individualName = new StringBuffer()
        for (Map.Entry<String, String> entry : map.entrySet()) {
            individualName.append(entry.getKey()).append(":").append(entry.getValue()).append(" ")
        }
        clustersMap.put(individualName.toString().trim(), map)
        clusterNames.append(individualName.toString()).append("\n")
    }
    clusterNames.append("${NO_DEPLOYMENT}")
    return clusterNames.toString()
}

def getEnvUrl(String envName) {
    def data = libraryResource "${CLUSTER_CONFIG_FILE}"
    return Helper.lookupClusterURL(data, envName)
}

def getEmergencyCMApprovers() {
    def data = libraryResource "${EMERGENCY_CM_APPROVERS_FILE}"
    return Helper.getEmergencyCmApprovers(data)
}


======== Constants.groovy ========
class Constants {

    static final String OPENSHIFT = 'openshift'
    static final String CLUSTERS = 'clusters'
    static final String NIGHTLY_JOB_BRANCH_REGEX_DEFAULT = "develop"
    static final String NIGHTLY_JOB_CRON_SCHEDULE_DEFAULT = "5 23 * * 1-5"
    static final String NIGHTLY_JOB_CRON_SCHEDULE_DEFAULT_ANDROID = "15 23 * * 1-5"

    enum StepName {
        BINARY_BUILD("Binary Build"),
        ARCHIVE_ARTIFACTS("Archive Artifacts"),
        NEXUS_IQ_SCAN("Nexus IQ Scan"),
        VERACODE_SCAN("Veracode Scan"),
        PARAMETERIZING("parameterizing"),
        VALIDATE_POM_FILE("Validate POM File"),
        PUBLISH_TO_NEXUS("Publish to Nexus"),
        TAG_BUILD("Tag build")

        String getName() {
            return name
        }

        StepName(String name) {
            this.name = name
        }
        private final String name

        String toString() {
            return name
        }
    }
}

======== abe_java_build_pipeline.groovy ========
/**
  * Most of the following is based on info from the Jenkins blog and documentation: 
  * https://jenkins.io/blog/2017/10/02/pipeline-templates-with-shared-libraries/
  * https://jenkins.io/doc/book/pipeline/shared-libraries/
  */
def call(body) {
    def pipelineParams = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    def BASE_IMAGE = ''
    def PLATFORM = ''
    def USER_DOC = 'https://prod-1-confluence.mufgamericas.com/display/CICDDocs/CICD+Pipelines+User+Documentation'
    def pom = ''
    def POM_PATH = 'pom.xml'
    def JDK = '11'
    def containerMap = [
            '11': "maven",
             '8': "maven-jdk8",
             '7': "maven-jdk7",
             '6': "maven-jdk6",
             '5': "maven-jdk5"
        ]
    def SECURITY_QUALITY_PASS_FLAG = true
    def pomQualityCheckOverrideProperties = [
      		'cpd.skip',
            'checkstyle.skip',
      		'pmd.skip',
      		'skip.pmd.check',
      		'maven.test.skip',
        ]

    pipeline {
        agent {
    	    kubernetes {
                cloud "${pipelineParams.openshiftCluster}-${pipelineParams.openshiftProject}"
    	        label "${JOB_BASE_NAME}-${BUILD_NUMBER}"    //This can't exceed 63 chars
                yaml libraryResource('agent-pod-conf/jenkins-abe-agent-pod.yaml')
    	    }
        }

        options {
            //GLOBAL BUILD OPTIONS
            buildDiscarder(logRotator(numToKeepStr: '8'))
            timeout(time: 45, unit: 'MINUTES')
        }

        environment {
          OPENSHIFT_CLUSTER_URL = "insecure://kubernetes.default.svc:443"
          OPENSHIFT_TOKEN = credentials("${pipelineParams.openshiftCluster}-${pipelineParams.openshiftProject}-jenkins")

          // This path is from a emptydir volume primarily for  multiple containers to share files
          MAVEN_ARTIFACTS = '/var/tmp/maven-artifacts'
        }
    
        stages {
           stage('Checkout Application Repo') {
               steps {
    		       container('maven') {
                       sh "mkdir mvn-build"
                       dir ("mvn-build") {

                           script {
                               // BBK_REPO_URL is propagated from the groovy script that create the build job
                               if (!env.BBK_REPO_BRANCH) {
                                 env.BBK_REPO_BRANCH = 'master'
                               }
                               git branch: "${env.BBK_REPO_BRANCH}", 
                                   url: "${BBK_REPO_URL}",
                                   credentialsId: 'bitbucket'
 
                               // Determine the target deployment platform with Puppet environment
                               if (fileExists('deployment.yaml')) {
                                   echo "Read Puppet deployment config from deployment.yaml"
                                   def deployConf = readYaml file: 'deployment.yaml'
                                   validateDeploymentYaml(deployConf)

                                   GSI = deployConf['gsi']
                                   PUPPET_SYSTEMID = deployConf['puppet-systemid']
                                   PLATFORM = deployConf['puppet-platform-type']
                                   JDK = deployConf['jdk'] ?: '11'
                                   POM_PATH = deployConf['pom-path'] ?: 'pom.xml'
                               } 

                               pom = readMavenPom file: POM_PATH
                               try{
                                   assert pom.parent.artifactId != null
                                   assert pom.parent.version != null
                                   assert pom.properties['component-type'] != null
                                   assert pom.properties['revision'] != null
                                   assert pom.properties['changelist'] != null
                               } catch (AssertionError e) {
                                   echo "Invalid pom.xml - See details at ${USER_DOC}"
                                   currentBuild.result = 'FAILURE'
                                   throw e
                               }

                               try{
                                   for (property in pomQualityCheckOverrideProperties) {
                                     overrideValue = pom.properties[property];                                     
                                     if (overrideValue != null) {
                                       echo "${property}: ${overrideValue}";
                                       assert !overrideValue.toBoolean();
                                     }
                                   }
                                 
                                   assert pom.properties['maven.pmd.maxAllowedViolations'] == null                                 
                               } catch (AssertionError e) {
                                   echo "Invalid POM Detected - Evil No Bad – Don’t turn off quality checks, this is against Bank Procedures - See details at ${USER_DOC}"
                                   currentBuild.result = 'FAILURE'
                                   throw e
                               }
                             
                               if (!fileExists('deployment.yaml')) {
                                   echo "Read Puppet deployment config from pom.xml"
                                   if ((pom.parent.artifactId).contains('-tomcat-')) {
                                       PLATFORM = 'tomcat'
                                   } else if ((pom.parent.artifactId).contains('jboss')) {
                                       PLATFORM = 'jboss'
                                   } else {
                                       PLATFORM = 'custom'
                                   }
                                   GSI = pom.properties['gsi-code']
                                   PUPPET_SYSTEMID = pom.properties['gsi-code']
                               }
                               sh (script: """
                                   echo "GSI: ${GSI}"
                                   echo "Puppet System Id: ${PUPPET_SYSTEMID}"
                                   echo "Puppet Platform Type: ${PLATFORM}"
                                   echo "JDK Version: ${JDK}"
                                   echo "POM: ${POM_PATH}"
                               """, returnStdout: true).trim()

                               // Determine the type of build for activating profile at the parent pom
                               if (("${CI_BUILD_TYPE}").contains('RELEASE')) {
                                   BUILD_TYPE = 'release'
                                   CHANGE_LIST = '-Dchangelist='
                                   // Add text to build history display
                                   manager.addShortText("${CI_BUILD_TYPE}", "white", "limegreen", "0px", "limegreen")
                               } else {
                                   BUILD_TYPE = 'snapshot'
                                   CHANGE_LIST = '-Dchangelist=-SNAPSHOT'
                                   // Add text to build history display
                                   manager.addShortText("${CI_BUILD_TYPE}", "white", "lightskyblue", "0px", "lightskyblue")
                               }
                               manager.addShortText(pom.properties['revision'], "white", "darkgray", "0px", "darkgray")

                               // Set up ENV for querydeploy.sh to consume
                               env.POM_GROUPID = pom.groupId

                               // pom.properties is a hash
                               // deployment-artifact-id property overrides pom.artifactId if exists. 
                               // This is a hack as there isn't a way to reliably determine artifactId in multi-module repo.
                               env.POM_ARTIFACTID = pom.artifactId
                               if (pom.properties.containsKey('deployment-artifact-id')) {
                                  env.POM_ARTIFACTID = pom.properties['deployment-artifact-id']
                                  echo "Read deployment-artifact-id from pom properties"
                               }

                               // deployment-artifact-packaging property overrides pom.packaging if exists. 
                               // This is a hack as there isn't a way to reliably determine packaging type in multi-module repo.
                               env.POM_PACKAGING = pom.packaging.toLowerCase() 
                               if (pom.properties.containsKey('deployment-artifact-packaging')) {
                                  env.POM_PACKAGING = pom.properties['deployment-artifact-packaging'].toLowerCase() 
                                  echo "Read deployment-artifact-packaging from pom properties"
                               }

                               env.POM_VERSION = sh (script: "mvn ${CHANGE_LIST} help:evaluate -Dexpression=project.version -DforceStdout -q -e",
                                                     returnStdout: true
                                                    ).trim()
                               // filename naming convention is taken from the parent/enterprise pom
                               env.POM_FINALNAME = "ub-${pom.properties['gsi-code']}-${pom.artifactId}-${pom.properties['component-type']}-${env.POM_VERSION}"
                           }
                       }
                   }
               }
           }
           stage('Maven Build') {
               steps {
                   container(containerMap["${JDK}"]) {
                       dir ("mvn-build") {

                           // Run Maven build
                           sh "mvn ${CHANGE_LIST} clean install " +
                              "-P ${BUILD_TYPE}"    
                              // BUILD_TYPE maps to a profile defined in parent pom 
                       }
                   }
               }
           }
           //stage('SonarQube Scan') {
           //    steps {
           //        container('maven') {
           //            dir ("mvn-build") {
           //                script {
           //                    try {
           //                        withSonarQubeEnv('SonarQube-OpenShiftPoC') {
           //                          // Run the maven build and trigger SonarQube scan
           //                          sh "mvn ${CHANGE_LIST} sonar:sonar " +
           //                             "-Dsonar.projectKey=${pom.groupId}:${pom.artifactId} " +
           //                             "-Dsonar.host.url=${SONAR_HOST_URL} " +
           //                             "-Dsonar.login=${SONAR_AUTH_TOKEN} " +
           //                             "\"-Dsonar.projectName=${pom.artifactId}\" " +
           //                             "\"-Dsonar.projectDescription=${pom.description}\" " +
           //                             '-Dsonar.java.binaries=target ' +
           //                             '-Dsonar.language=java ' +
           //                             '-Dsonar.sourceEncoding=UTF-8 ' +
           //                             "-P ${BUILD_TYPE}"    
           //                             // BUILD_TYPE maps to a profile defined in parent pom 
           //                        }
           //                    } catch (Exception e) {
           //                        echo "Failed to perform SonarQube Scan...Ignore error and continue"
           //                        SECURITY_QUALITY_PASS_FLAG &= false
           //                    }
           //                }
           //            }
           //        }
           //    }
           //}
           stage('Nexus IQ Scan') {
		      steps {
		      	   container('maven') {
                      dir ("mvn-build") {
                         script {
                          catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE', SECURITY_QUALITY_PASS_FLAG: (SECURITY_QUALITY_PASS_FLAG & false)) {
                            nexusIQScan(
                                IQ_STAGE: (("${CI_BUILD_TYPE}").contains('RELEASE')) ? 'release' : 'build',
                                GSI: pom.properties['gsi-code'],
                                ARTIFACT_ID: pom.artifactId
                            )
                          }
                         }
                      }
		      	   }
		      }
		   }
           stage('Upload Artifacts for Veracode Scan') {
               steps {
    	           container('maven') {
                       dir ("mvn-build") {
                           script {
                                catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE', SECURITY_QUALITY_PASS_FLAG: (SECURITY_QUALITY_PASS_FLAG & false)) {
                                    veracodeScan(
                                            buildType: BUILD_TYPE,
                                            gsiCode: pom.properties['gsi-code']
                                    )
                                }
                           }
                       }
                   }
               }
           }
           stage('Maven Deploy') {
               when {
                  allOf {
                    expression {SECURITY_QUALITY_PASS_FLAG == true}
                  }
                }
    	       steps {
    		       container('maven') {
                       dir ("mvn-build") {
                           // Run Maven build
                           sh "mvn ${CHANGE_LIST} deploy -e " +
                              "-P ${BUILD_TYPE}"    
                              // BUILD_TYPE maps to a profile defined in parent pom 
                       }
    		       }
    	       }
    	   }
           stage('Create Puppet Deployment Config') {
               when {
                  allOf {
                    expression {SECURITY_QUALITY_PASS_FLAG == true}
                  }
                }
                options { retry(3) }
                steps {
    		       container('maven') {
                       withCredentials([
                           usernamePassword(credentialsId: 'puppet-stash', usernameVariable: 'PE_USERNAME', passwordVariable: 'PE_PASSWORD'),
                           usernamePassword(credentialsId: 'nexus-prod-user-token', usernameVariable: 'NEXUS_USERNAME', passwordVariable: 'NEXUS_PASSWORD')]) {
                           sh "pipelines/bins/queuedeploy.sh create ./mvn-build/${POM_PATH} ${env.POM_PACKAGING} ${GSI}-${BBK_PROJECT}/${BBK_REPO} ${PUPPET_CONFIG_BUILD_NUMBER}/${PLATFORM} ${PUPPET_SYSTEMID}_${BBK_PROJECT}_${pom.artifactId}"

                       }
                    }
                }
           }
         
        }
        post {
            success {
                container('jnlp') {
                    script {
                        if (("${CI_BUILD_TYPE}").contains('RELEASE')) {
                            // Keep RELEASE build forever -  https://support.cloudbees.com/hc/en-us/articles/218762207-Automatically-Marking-Jenkins-builds-as-keep-forever
                            currentBuild.keepLog = true 
                            echo "Successful RELEASE build - Mark to Keep Forever"
                        }
                    }
                }
            }
        }
    }
}

def validateDeploymentYaml(Map deployConf) {
    // TO-DO - use an actual schema validator and externalize this code as a common function
    // This can only check required fields and not optional ones
    def schema = [
        "gsi": ~/[a-z0-9]{3,4}/,
        "puppet-systemid": ~/^\w+([_-]\w+)*$/,
        "puppet-platform-type": ~/custom|websphere|jboss.*|ews.*|tomcat|utility/
    ]
    schema.each { key, regex ->
        assert deployConf[key]?.trim() : "${schema[key]} is required"
        assert (deployConf[key] ==~ schema[key]) : "${key}: ${deployConf[key]} doesn't match ${schema[key]}"
    }
}
======== abe_java_release_pipeline.groovy ========
/**
  * Most of the following is based on info from the Jenkins blog and documentation: 
  * https://jenkins.io/blog/2017/10/02/pipeline-templates-with-shared-libraries/
  * https://jenkins.io/doc/book/pipeline/shared-libraries/
  */
def call(body) {
    def pipelineParams = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    def BUILD_NAME = ''
    def BASE_IMAGE = ''
    def PLATFORM = ''
    def USER_DOC = 'https://prod-1-confluence.mufgamericas.com/display/CICDDocs/CICD+Pipelines+User+Documentation'
    def pom = ''
    def POM_PATH = 'pom.xml'


    pipeline {
        agent {
    	    kubernetes {
                cloud "${pipelineParams.openshiftCluster}-${pipelineParams.openshiftProject}"
    	        label "${JOB_BASE_NAME}-${BUILD_NUMBER}"    //This can't exceed 63 chars
                yaml libraryResource('agent-pod-conf/jenkins-abe-agent-pod.yaml')
    	    }
        }

        options {
            //GLOBAL BUILD OPTIONS
            buildDiscarder(logRotator(numToKeepStr: '8'))
            timeout(time: 45, unit: 'MINUTES')
        }

        environment {
          OPENSHIFT_CLUSTER_URL = "insecure://kubernetes.default.svc:443"
          OPENSHIFT_TOKEN = credentials("${pipelineParams.openshiftCluster}-${pipelineParams.openshiftProject}-jenkins")
        }
    
        stages {
           stage('Checkout Application Repo') {
               steps {
    		       container('maven') {
                       sh "mkdir mvn-build"
                       dir ("mvn-build") {

                           script {
                               // BBK_REPO_URL is propagated from the groovy script that create the build job
                               if (!env.BBK_REPO_BRANCH) {
                                 env.BBK_REPO_BRANCH = 'master'
                               }
                               git branch: "${env.BBK_REPO_BRANCH}", 
                                   url: "${BBK_REPO_URL}",
                                   credentialsId: 'bitbucket'

                               // Determine the target deployment platform with Puppet environment
                               if (fileExists('deployment.yaml')) {
                                   echo "Read Puppet deployment config from deployment.yaml"
                                   def deployConf = readYaml file: 'deployment.yaml'
                                   validateDeploymentYaml(deployConf)

                                   GSI = deployConf['gsi']
                                   PUPPET_SYSTEMID = deployConf['puppet-systemid']
                                   PLATFORM = deployConf['puppet-platform-type']
                                   POM_PATH = deployConf['pom-path'] ?: 'pom.xml'
                               }

                               // TO-DO - Add catch FileNotFound error in case when pom.xml is not at root of repo
                               pom = readMavenPom file: POM_PATH
                               try{
                                   assert pom.parent.artifactId != null
                                   assert pom.parent.version != null
                                   assert pom.properties['component-type'] != null
                                   assert pom.properties['revision'] != null
                                   assert pom.properties['changelist'] != null
                               } catch (AssertionError e) {
                                   echo "Invalid pom.xml - See details at ${USER_DOC}"
                                   currentBuild.result = 'FAILURE'
                                   throw e
                               }

                               if (!fileExists('deployment.yaml')) {
                                   echo "Read Puppet deployment config from pom.xml"
                                   if ((pom.parent.artifactId).contains('-tomcat-')) {
                                       PLATFORM = 'tomcat'
                                   } else if ((pom.parent.artifactId).contains('jboss')) {
                                       PLATFORM = 'jboss'
                                   } else {
                                       PLATFORM = 'custom'
                                   }
                                   GSI = pom.properties['gsi-code']
                                   PUPPET_SYSTEMID = pom.properties['gsi-code']
                               }
                               sh (script: """
                                   echo "GSI: ${GSI}"
                                   echo "Puppet System Id: ${PUPPET_SYSTEMID}"
                                   echo "Puppet Platform Type: ${PLATFORM}"
                                   echo "POM: ${POM_PATH}"
                               """, returnStdout: true).trim()


                               // Block SNAPSHOT build from deploying into non-DEV environment 
                               if (("${CI_BUILD_TYPE}").contains('SNAPSHOT') && !"${PUPPET_DEPLOY_ENV}".contains('dev')) {
                                  error("SNAPSHOT build can only be deployed to DEV environment.")
                               }

                               // Add text to build history display
                               if (("${CI_BUILD_TYPE}").contains('RELEASE')) {
                                   manager.addShortText("${CI_BUILD_TYPE}", "white", "limegreen", "0px", "limegreen")
                               } else { 
                                   manager.addShortText("${CI_BUILD_TYPE}", "white", "lightskyblue", "0px", "lightskyblue")
                               }
                           }
                       }
                   }
               }
           }
           stage('Run queuedeploy-autosys.sh - move yaml in PE deployment repo ') {
                options { retry(3) }
                steps {
    		       container('maven') {
                       withCredentials([
                           usernamePassword(credentialsId: 'puppet-stash', usernameVariable: 'PE_USERNAME', passwordVariable: 'PE_PASSWORD'),
                           usernamePassword(credentialsId: 'nexus-user-token', usernameVariable: 'NEXUS_USERNAME', passwordVariable: 'NEXUS_PASSWORD')]) {
                           sh "echo PUPPET_CONFIG_BUILD_NUMBER: ${PUPPET_CONFIG_BUILD_NUMBER}"
                           sh "echo PUPPET_DEPLOY_ENV: ${PUPPET_DEPLOY_ENV}"
                           sh "echo CI_BUILD_TYPE: ${CI_BUILD_TYPE}"
                           sh "pipelines/bins/queuedeploy.sh queue ${GSI}-${BBK_PROJECT}/${BBK_REPO} ${PUPPET_CONFIG_BUILD_NUMBER}/${PLATFORM} ${PUPPET_SYSTEMID}_${BBK_PROJECT}_${pom.artifactId} ${PUPPET_DEPLOY_ENV}/${PLATFORM}"
                       }
                    }
                }
           }
        }
        post {
            success {
                container('jnlp') {
                    script {
                        if (("${CI_BUILD_TYPE}").contains('RELEASE')) {
                            // Keep RELEASE build forever -  https://support.cloudbees.com/hc/en-us/articles/218762207-Automatically-Marking-Jenkins-builds-as-keep-forever
                            currentBuild.keepLog = true
                            echo "Successful RELEASE build - Mark to Keep Forever"
                        }
                    }
                }
            }
        }
    }
}

def validateDeploymentYaml(Map deployConf) {
    // TO-DO - use an actual schema validator and externalize this code as a common function
    // This can only check required fields and not optional ones
    def schema = [
        "gsi": ~/[a-z0-9]{3,4}/,
        "puppet-systemid": ~/^\w+([_-]\w+)*$/,
        "puppet-platform-type": ~/custom|websphere|jboss.*|ews.*|tomcat|utility/
    ]
    schema.each { key, regex ->
        assert deployConf[key]?.trim() : "${schema[key]} is required"
        assert (deployConf[key] ==~ schema[key]) : "${key}: ${deployConf[key]} doesn't match ${schema[key]}"
    }
}
======== android_build_pipeline_multibranch.groovy ========
import groovy.json.JsonSlurper

def call(
        String openshiftCluster = "",
        String openshiftProject = "",
        String dockerRegistryURL = "",
        String nexusUserTokenId = "",
        String buildOption = "",
        String deploymentConfig = "",
        String nexusBaseUrl = "",
        String nightlyJobBranches = Constants.NIGHTLY_JOB_BRANCH_REGEX_DEFAULT,
        String nightlyJobCronSchedule = Constants.NIGHTLY_JOB_CRON_SCHEDULE_DEFAULT_ANDROID
) {

    assert deploymentConfig?.trim(): "Deployment Configuration Map is required: <Environment, Build File Pattern>"
    assert nexusBaseUrl?.trim(): "Deployment nexus url is required."
    assert buildOption?.trim(): "Build command options are required."

    def DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX = /release.*|hotfix.*|develop|master/
    def SECURITY_QUALITY_PASS_FLAG = true
    def VERACODE_SCAN_BRANCH_REGEX = /release.*|hotfix.*|develop|master/
    def NIGHTLY_JOB_BRANCH_REGEX = env.NIGHTLY_JOB_BRANCH_REGEX ?: nightlyJobBranches

    DEPLOYMENT_CONFIG = "${deploymentConfig}"
    BUILD_OPTIONS = "${buildOption}"

    NO_DEPLOYMENT = 'No Deployment'

    pipeline {
        agent {
            kubernetes {
                cloud "${openshiftCluster}-${openshiftProject}"
                label "jenkins-agent-pod-android-${openshiftProject}-${BUILD_NUMBER}"
                yaml libraryResource('agent-pod-conf/jenkins-agent-pod-android.yaml')
            }
        }

        options {
            //GLOBAL BUILD OPTIONS
            buildDiscarder(logRotator(numToKeepStr: '8'))
            timeout(time: 45, unit: 'MINUTES')
        }

        environment {
            NEXUS_REGISTRY_URL = "${dockerRegistryURL}"
            // This path is from a emptydir volume primarily for  multiple containers to share files
            MAVEN_ARTIFACTS = '/var/tmp/maven-artifacts'

            // These are for oc to make outbound connection to the OpenShift cluster URL
            PROXY = 'http://ub-app-proxy.uboc.com:80'
            HTTP_PROXY = "${env.PROXY}"
            HTTPS_PROXY = "${env.PROXY}"
        }

        parameters {
            choice(name: "Build", choices: ['Default', 'Production Release'], description: 'Build Command to overwrite default: Production release -- assembleDevDebug assembleUatRelease assembleProdRelease')
            choice(name: "Deployment", choices: "${parseDeployment()}", description: "Choose the environment to deploy.")
        }

        triggers {
            cron(("${env.BRANCH_NAME}" =~ ~/${NIGHTLY_JOB_BRANCH_REGEX}/) ? (env.NIGHTLY_JOB_CRON_SCHEDULE || nightlyJobCronSchedule) : "")
        }

        stages {
            stage("parameterizing") {
                steps {
                    script {
                        if ("${params.Invoke_Parameters}" == "Yes") {
                            currentBuild.result = 'ABORTED'
                            error('DRY RUN COMPLETED. JOB PARAMETERIZED.')
                        }
                        log.status("parameterizing", currentBuild.result)
                        log.info("Cron Schedule:" + ("${env.BRANCH_NAME}" =~ ~/${NIGHTLY_JOB_BRANCH_REGEX}/) ? (env.NIGHTLY_JOB_CRON_SCHEDULE || nightlyJobCronSchedule) : "")
                    }
                }
            }

            stage('Gradle Build') {
                steps {
                    container('android') {
                        script {
                            def keyFile = libraryResource encoding: 'Base64', resource: 'com/mufg/tsi/devsecops/pipelines/keystores/ubrelease-key.keystore'
                            writeFile encoding: 'Base64', file: 'ub-key.jks', text: "${keyFile}"
                            VERSION_TAG = sh(returnStdout: true, script: 'mvn org.apache.maven.plugins:maven-help-plugin:3.2.0:evaluate -Dexpression=project.version -q -DforceStdout').trim()

                            def buildCommand = "${params.Build}" != "Default" ? getReleaseBuildOption() : getBuildOptions("${BRANCH_NAME}", "${VERSION_TAG}")
                            withCredentials([
                                    usernamePassword(credentialsId: "${nexusUserTokenId}", usernameVariable: 'nexusUsername', passwordVariable: 'nexusPassword'),
                                    string(credentialsId: 'android-keystore-password', variable: 'androidKeystorePassword'),
                                    string(credentialsId: 'android-keystore-alias-password', variable: 'androidKeystoreAliasPassword')]) {
                                sh "gradle clean ${buildCommand} -PnexusUsername=${nexusUsername} -PnexusPassword=${nexusPassword} -PKEYSTORE_PW=${androidKeystorePassword} -PALIAS_PW=${androidKeystoreAliasPassword} -PKEYSTORE_FILE=${WORKSPACE}/ub-key.jks -PNEXUS_REPO=${nexusBaseUrl}/android-group/"
                            }
                            log.status(Constants.StepName.BINARY_BUILD.name, currentBuild.result)
                        }
                    }
                }
            }

            stage("Archive Artifacts") {
                steps {
                    container('android') {
                        script {
                            archiveArtifacts allowEmptyArchive: true,
                                    artifacts: '**/*.apk, **/*.aab, unionbankclient/build/**/mapping/**/*.txt, unionbankclient/build/**/logs/**/*.txt'
                            log.status(Constants.StepName.ARCHIVE_ARTIFACTS.name, currentBuild.result)
                        }
                    }
                }
            }

            stage('Nexus IQ Scan') {
                when {
                    not { buildingTag() }
                }
                steps {
                    container('android') {
                        script {
                            catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE', SECURITY_QUALITY_PASS_FLAG: (SECURITY_QUALITY_PASS_FLAG & false)) {
                                nexusIQScan(
                                        IQ_STAGE: (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) ? 'release' : 'build',
                                        GSI: 'tab',
                                        ARTIFACT_ID: 'android'
                                )
                            }
                            log.status(Constants.StepName.NEXUS_IQ_SCAN.name, currentBuild.result)
                        }
                    }
                }
            }

            stage('Upload Artifacts for Veracode Scan') {
                when {
                    allOf {
                        expression { env.BRANCH_NAME =~ VERACODE_SCAN_BRANCH_REGEX }
                        not { buildingTag() }
                    }
                }
                steps {
                    container('android') {
                        script {
                            catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE', SECURITY_QUALITY_PASS_FLAG: (SECURITY_QUALITY_PASS_FLAG & false)) {
                                //resolve unknown usr error for scp
                                //https://blog.openshift.com/jupyter-on-openshift-part-6-running-as-an-assigned-user-id/
                                sh "/usr/local/bin/set-user-id"
                                veracodeScan(
                                        buildType: (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) ? 'release' : 'snapshot',
                                        // gsiCode: env.GSI
                                        gsiCode: "TAB (Android)"
                                )
                                log.status(Constants.StepName.VERACODE_SCAN.name, currentBuild.result)
                            }
                        }
                    }
                }
            }

            stage("Publish to Nexus") {
                when {
                    allOf {
                        expression { SECURITY_QUALITY_PASS_FLAG }
                        not { buildingTag() }
                    }
                }
                steps {
                    container('android') {
                        script {
                            def nexusUrl = ''
                            if ("${VERSION_TAG}".contains("SNAPSHOT")) {
                                nexusUrl = "${nexusBaseUrl}/maven-snapshots/"
                            } else {
                                nexusUrl = "${nexusBaseUrl}/maven-releases/"
                            }
                            println "${VERSION_TAG}"
                            println "${nexusUrl}"

                            sh('''
                               for file in $(find ${WORKSPACE} -name "*.apk" -type f); do
                                    file_wo_path=${file##*/}
                                    pom_file_version=''' + VERSION_TAG + '''
                                    echo "file to be published: ${file_wo_path%\\.*} "
                                    mvn deploy:deploy-file -f pom.xml -DgroupId=com.unionbank.tab -DartifactId=${file_wo_path%\\.*} -Dversion="${pom_file_version}" -DpomFile=pom.xml -Dpackaging=apk -DrepositoryId=snapshots -Durl=''' + nexusUrl + ''' -Dfile=${file}
                               done
                              ''')
                            log.status(Constants.StepName.PUBLISH_TO_NEXUS.name, currentBuild.result)
                        }
                    }
                }
            }

            stage("Tag build") {
                when {
                    allOf {
                        expression { SECURITY_QUALITY_PASS_FLAG }
                        not { buildingTag() }
                    }
                }
                environment {
                    GIT_AUTH = credentials('bitbucket-tagging')
                }
                steps {
                    container('android') {
                        script {
                            if ((env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) && "${env.BUILD_OPTIONS}".contains("RELEASE")) {
                                try {
                                    def tag_name = "v${VERSION_TAG}.${BUILD_NUMBER}".replaceAll("-SNAPSHOT", "")

                                    sh "git tag ${tag_name} -am \"Jenkins release tag\""
                                    sh('''
                                    git config --local credential.helper "!f() { echo username=\\$GIT_AUTH_USR; echo password=\\$GIT_AUTH_PSW; }; f"
                                    git push origin --tags
                                ''')
                                } catch (error) {
                                    echo "Exception in tagging: " + error.getMessage()
                                    throw error
                                }
                            } else {
                                echo "It is not a release. Bypassing tagging."
                            }
                            log.status(Constants.StepName.TAG_BUILD.name, currentBuild.result)
                        }
                    }
                }
            }
        }
    }
}

def getBuildOptions(String branchName, String pomVersion) {
    def jsonSlurper = new JsonSlurper()
    Map<String, String> configMap = jsonSlurper.parseText("${BUILD_OPTIONS}")
    String buildConfig = "DEFAULT"
    if (!pomVersion.contains("SNAPSHOT")) {
        buildConfig = "RELEASE"
    }
    if (branchName.startsWith("PR-")) {
        buildConfig = "PR"
    }
    return configMap.get(buildConfig);
}

def getReleaseBuildOption() {
    def jsonSlurper = new JsonSlurper()
    Map<String, String> configMap = jsonSlurper.parseText("${BUILD_OPTIONS}")
    return configMap.get("RELEASE");
}

def parseDeployment() {
    def jsonSlurper = new JsonSlurper()
    echo "deployment config map: ${DEPLOYMENT_CONFIG}"
    Map<String, String> configMap = jsonSlurper.parseText("${DEPLOYMENT_CONFIG}")
    StringBuffer individualName = new StringBuffer()
    for (Map.Entry<String, String> entry : configMap.entrySet()) {
        individualName.append(entry.getKey()).append("\n")
    }
    individualName.append("${NO_DEPLOYMENT}")
    return individualName.toString()
}

def getFilePattern(String environment) {
    def jsonSlurper = new JsonSlurper()
    Map<String, String> configMap = jsonSlurper.parseText("${DEPLOYMENT_CONFIG}")
    return configMap.get(environment)
}
======== angular_build_pipeline_multibranch.groovy ========
/**
 * Most of the following is based on info from the Jenkins blog and documentation:
 * https://jenkins.io/blog/2017/10/02/pipeline-templates-with-shared-libraries/
 * https://jenkins.io/doc/book/pipeline/shared-libraries/
 */
import com.mufg.tsi.devsecops.pipelines.Helper

class BuildConfiguration {
    def id = ""
    def baseImage = ""
    def buildOption = ""
    def packagingType = ""
}

def call(
        String openshiftCluster = "",
        String openshiftProject = "",
        String dockerRegistryURL = "",
        String baseImageFrontend = "rhscl/httpd-24-rhel7:2.4-109",
        String nexusUrl = "",
        String nexusArtifactRepo = "angular-artifacts",
        String nexusCredentialId = "nexus-user-token",                                                                 
        String npmRepo = "",
        String sonarQubeEnv = ""
) {

    assert openshiftCluster?.trim(): "openshift Cluster is required."
    assert openshiftProject?.trim(): "openshift Project is required."
    assert dockerRegistryURL?.trim(): "dockerRegistryURL is required."
    assert env.OPENSHIFT_APP_PROJECTS?.trim(): "Env OPENSHIFT_APP_PROJECTS is required"

    def DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX = /release.*|hotfix.*|develop|master/
    def SECURITY_QUALITY_PASS_FLAG = true
    def DEFAULT_SONAR_ENV = 'SonarQube-OpenShiftPoC'
    def LOG_LEVEL = 2
    def RELEASE_VERSION = env.BRANCH_NAME
    def USER_DOC = 'https://prod-1-confluence.mufgamericas.com/display/CICDDocs/CICD+Pipelines+User+Documentation#CICDPipelinesUserDocumentation-PrerequisitesPrerequisites'
    BuildConfiguration[] buildConfigurations = [
            new BuildConfiguration(id: "angular", baseImage: env.BASE_IMAGE_FRONTEND ?: baseImageFrontend, buildOption: env.BUILD_OPTION_FRONTEND ?: "", packagingType: "container"),
    ] as BuildConfiguration[]

    NO_DEPLOYMENT = 'No Deployment'
    CLUSTER_CONFIG_FILE = 'com/mufg/tsi/devsecops/pipelines/openshift.yaml'
    VERACODE_SCRIPTS = 'com/mufg/tsi/devsecops/pipelines/bin/vcode_helper.sh'

    BUILD_GIT_SRC_REPO = "https://bbk.unionbank.com/scm/dsops/groovy_sharedlibs.git"
    BUILD_GIT_BRANCH = env.BUILD_GIT_BRANCH ? env.BUILD_GIT_BRANCH : "master"
    BUILD_CONTEXT_DIR = "resources/docker-build/angular"

    final Map<String, Map<String, String>> CLUSTER_MAP = new HashMap<>()

    pipeline {
        agent {
            kubernetes {
                cloud "${openshiftCluster}-${openshiftProject}"
                label "jenkins-${openshiftCluster}-${openshiftProject}-${BUILD_NUMBER}"
                //This can't exceed 63 chars
                yaml libraryResource('agent-pod-conf/jenkins-agent-pod-js.yaml')
            }
        }
        options {
            //GLOBAL BUILD OPTIONS
            buildDiscarder(logRotator(numToKeepStr: '13'))
            disableConcurrentBuilds()
            timeout(time: 45, unit: 'MINUTES')
        }

        environment {
            
            // This path is from a emptydir volume primarily for  multiple containers to share files
            ARTIFACTS_DIR = '/var/tmp/artifacts'

            // These are for oc to make outbound connection to the OpenShift cluster URL
            PROXY='http://ub-app-proxy.uboc.com:80'
            HTTP_PROXY="${env.PROXY}"
            HTTPS_PROXY="${env.PROXY}"
            NO_PROXY="kubernetes.default.svc"
           
            // Parse the BitBucket Project and Repo from the URL 
            BBK_PROJECT="${env.GIT_URL.split('/')[4]}"
            //BBK_REPO="${(env.GIT_URL.split('/')[5]).split('.')[0]}"
            BBK_REPO="${env.GIT_URL.split('/')[5]}"

            OPENSHIFT_CLUSTER_URL = "insecure://kubernetes.default.svc:443"
            OPENSHIFT_TOKEN = credentials("${openshiftCluster}-${openshiftProject}-jenkins")
        }

        parameters {
            choice(name: "Deployment", choices: "${fetchClustersFromOnboardingFile(CLUSTER_MAP)}", description: "Choose the environment to deploy.")
        }

        stages {
            stage("parameterizing") {
                steps {
                    script {
                        if ("${params.Invoke_Parameters}" == "Yes") {
                            currentBuild.result = 'ABORTED'
                            error('DRY RUN COMPLETED. JOB PARAMETERIZED.')
                        }
                    }
                }
            }

            stage('Validate package.json') {
                steps {
                    container('node') {
                        script {
                            try {
                                env.ARTIFACTID = sh (script: "cat package.json | jq -r .name", returnStdout: true).trim()
                                env.VERSION = sh (script: "cat package.json | jq -r .version", returnStdout: true).trim()

                                assert env.ARTIFACTID != null
                                assert env.VERSION != null
                              
                            } catch (AssertionError e) {
                                echo "Invalid package.json - See details at ${USER_DOC}"
                                currentBuild.result = 'FAILURE'
                                throw e
                            }
                            try {
                                assert env.GSI != null
                            } catch (AssertionError e) {
                                echo "Missing critical config parameters at job config"
                                currentBuild.result = 'FAILURE'
                                throw e
                            }
                            env.ARTIFACTFILE = "${env.ARTIFACTID}-${env.VERSION}-${env.BUILD_NUMBER}.zip"

                            if (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) {
                                env.VERSION = "${env.VERSION}" + "${escapeBranch()}"
                                echo "Release version: ${env.VERSION}"
                            } else {
                                echo "Non release version: ${env.VERSION} ${env.BRANCH_NAME}"
                                if (env.TAG_NAME) {
                                    env.VERSION = env.BRANCH_NAME
                                }
                            }
                            echo "version original: ${env.VERSION} "
                            echo "branch ${escapeBranch()}"
                            echo "Build Number: ${env.BUILD_NUMBER}"
                            echo "VERSION: ${env.VERSION}"
                        }
                    }
                }
            }

            stage('Build Binaries') {
                when {
                    not { buildingTag() }
                }
                steps {
                    container('node') {
                        script {
                            withCredentials([
                            usernamePassword(credentialsId: "${nexusCredentialId}",
                                             usernameVariable: 'NEXUS_USERNAME',
                                             passwordVariable: 'NEXUS_PASSWORD')]) {
                                sh (script: """
                                    # Set up npm
                                    npm config set registry ${npmRepo}
                                    npm config set cafile /var/tmp/certs/ca-cert.ca
                                    set +x  # Hide the token from log
                                    echo _auth=\$(echo -n \"${NEXUS_USERNAME}:${NEXUS_PASSWORD}\"|base64) >> /home/node/.npmrc
                                    set -x

                                    # Build and package app
                                    npm install
                                    ng build --prod
                                    (cd dist/${env.ARTIFACTID} && zip -r ${ARTIFACTS_DIR}/${env.ARTIFACTFILE} .)
                                """, returnStdout: true).trim()
                            }
                        }
                    }
                }
            }

            //stage('SonarQube Scan') {
            //    when {
            //        not { buildingTag() }
            //    }
            //    steps {
            //        container('maven') {
            //            script {
            //                try {
            //                    withSonarQubeEnv(sonarQubeEnv ?: DEFAULT_SONAR_ENV) {
            //                        sh "mvn sonar:sonar -P sonar -Dsonar.host.url=${SONAR_HOST_URL} -Dsonar.login=${SONAR_AUTH_TOKEN}"
            //                    }
            //                } catch (error) {
            //                    echo "Failed to perform SonarQube Scan...Ignore error and continue " + error.getMessage()
            //                }
            //            }
            //        }
            //    }
            //}

           stage('Nexus IQ Scan') {
              when {
                  not { buildingTag() }
              }
              steps {
                  container('node') {
                      script {
                          catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE', SECURITY_QUALITY_PASS_FLAG: (SECURITY_QUALITY_PASS_FLAG & false)) {
                            nexusIQScan(
                                IQ_STAGE: (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX ) ? 'release' : 'build',
                                GSI: env.GSI,
                                ARTIFACT_ID: env.ARTIFACTID
                            )
                          }                         
                      }
                  }
              }
           }
           
            //stage('Veracode Scan') {
            //    when {
            //        not { buildingTag() }
            //    }
            //    steps {
            //        container('maven') {
            //            script {
            //                veracodeScan(
            //                        buildType: (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) ? 'release' : 'snapshot',
            //                        gsiCode: POM.properties['gsi-code']
            //                )
            //            }
            //        }
            //    }
            //}

            stage('Publish Artifacts') {
                when {
                  allOf {
                    expression {SECURITY_QUALITY_PASS_FLAG == true}
                    not { buildingTag() }
                  }
                }
                environment {
                    GIT_AUTH = credentials('bitbucket-tagging')
                }
                steps {
                    script {
                        container('jnlp') {
                            if (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) {
                                try {
                                    RELEASE_VERSION = "v${env.VERSION}"

                                    sh "git config --global user.name 'Jenkins Agent'"
                                    sh "git config --global user.email 'unused@us.mufg.jp'"
                                    sh "git tag ${RELEASE_VERSION} -am \"Jenkins release tag\""
                                    sh('''
                                    git config --local credential.helper "!f() { echo username=\\$GIT_AUTH_USR; echo password=\\$GIT_AUTH_PSW; }; f"
                                    git push origin --tags
                                    ''')
                                } catch (error) {
                                    echo "Exception in deploy: " + error.getMessage()
                                    throw error
                                }

                                // Publish artifact to Nexus
                                withCredentials([
                                usernamePassword(credentialsId: "${nexusCredentialId}",
                                                 usernameVariable: 'NEXUS_USERNAME',
                                                 passwordVariable: 'NEXUS_PASSWORD')]) {
    
                                    env.NEXUS_CONTENT_PATH = "${env.GSI}/${BBK_PROJECT}/${BBK_REPO}/${env.VERSION}"
                                    String http_status = sh (script: """
                                        curl -k -u ${NEXUS_USERNAME}:${NEXUS_PASSWORD} -X POST \
                                          ${nexusUrl}/service/rest/v1/components?repository=${nexusArtifactRepo} \
                                          -F raw.directory=/${env.NEXUS_CONTENT_PATH} \
                                          -F raw.asset1=@${ARTIFACTS_DIR}/${env.ARTIFACTFILE} \
                                          -F raw.asset1.filename=${env.ARTIFACTFILE} \
                                          -w %{http_code}
                                        """, returnStdout: true).trim()
    
                                        switch(http_status) { 
                                            case ~/.*204.*/: 
                                               echo "Artifact Uploaded Successfully"
                                           break 
                                        case ~/.*400.*/: 
                                           error("The artifact may have been previously uploaded.")
                                           break 
                                        default: 
                                           error("Unknown status - " + http_status)
                                           break 
                                        }
                                }

                                // Build container and publish to Nexus
                                dir('build-docker-image') {
                                    // Keep buildConfiguration here to align with Java pipeline's implementation
                                    def buildConfiguration = buildConfigurations.find {"angular"} ?: buildConfigurations[0]

                                    // Get buildconfig and build params from lib resource for oc to create build
                                    data = libraryResource "docker-build/${buildConfiguration.id}/buildconfig_docker.yaml"
                                    writeFile file: "buildconfig_docker.yaml", text: data
                                    data = libraryResource "docker-build/${buildConfiguration.id}/docker-build-params"
                                    writeFile file: "docker-build-params", text: data

                                    ARTIFACT_URL = "${nexusUrl}/repository/${nexusArtifactRepo}/${env.NEXUS_CONTENT_PATH}/${env.ARTIFACTFILE}"
                                    openshift.logLevel(1)
                                    openshift.withCluster( "${OPENSHIFT_CLUSTER_URL}" ) {
                                        openshift.withCredentials( "${OPENSHIFT_TOKEN}" ) {
                                            openshift.withProject( "${openshiftProject}" ) {

                                                BUILD_NAME="${BUILD_TAG.replace("~", "-")}"

                                                def models = openshift.process("-f", "buildconfig_docker.yaml", \
                                                "-p", "BUILD_NAME=$BUILD_NAME", \
                                                "-p", "GIT_SRC_REPO=${BUILD_GIT_SRC_REPO}", \
                                                "-p", "GIT_BRANCH=${BUILD_GIT_BRANCH}", \
                                                "-p", "CONTEXT_DIR=${BUILD_CONTEXT_DIR}", \
                                                "-p", "FROM_IMAGE_REGISTRY=${dockerRegistryURL}", \
                                                "-p", "FROM_IMAGE=${buildConfiguration.baseImage.split(':')[0]}", \
                                                "-p", "FROM_IMAGE_TAG=${buildConfiguration.baseImage.split(':')[1]}", \
                                                "-p", "TO_IMAGE_REGISTRY=${dockerRegistryURL}", \
                                                "-p", "IMAGE_NAMESPACE=${env.GSI}/", \
                                                "-p", "TO_IMAGE=${env.ARTIFACTID}", \
                                                "-p", "TO_IMAGE_TAG=${RELEASE_VERSION}", \
                                                "-p", "ARTIFACT_URL=${ARTIFACT_URL}", \
                                                "--param-file=docker-build-params"
                                                )
                                                openshift.logLevel(0)
                                                def created = openshift.create( models )
                                                def bc = created.narrow('bc')
                                                def build = bc.startBuild()
                                                build.logs('-f') // This will run to the end of the build
                                                if ( build.object().status.phase == "Failed" ) {
                                                    error("${build.object().status.reason}")
                                                }
                                                echo "Built and Pushed Image - ${build.object().status.outputDockerImageReference}"
                                            }
                                        }
                                    }
                                }
                            } else {
                                echo "Code is not in a deploy branch. Bypassing deployment build."
                            }
                        }
                    }
                }
            }

            stage('Deploy to Target Environment') {
                when {
                  allOf {
                    expression {SECURITY_QUALITY_PASS_FLAG == true}
                  }
                }
                steps {
                    container('jnlp') {
                        script {
                            if ((!(env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) && !env.TAG_NAME)
                                    || "${NO_DEPLOYMENT}" == "${params.Deployment}"
                                    || "${params.Deployment}" == "") {
                                echo "No deployment."
                            } else {
                                echo "Deplolyment clusters: ${params.Deployment}"
                                if (env.K8S_DEPLOYMENT_REPOSITORY) {
                                    //assuming deployment repo has the same branch name as environment name except prd for master
                                    assert env.K8S_DEPLOYMENT_CONFIG: "K8S_DEPLOYMENT_CONFIG is required to deploy. Assuming the IMAGE_VERSION will be replaced with current build version"
                                    String deploymentName = params.Deployment
                                    Map<String, String> clusterEnv = CLUSTER_MAP.get(deploymentName.trim())
                                    String branchName = clusterEnv.get("environment")
                                    branchName = "prd".equalsIgnoreCase(branchName) ? "master" : branchName
                                    dir('deployment-repo') {
                                        git branch: branchName, url: env.K8S_DEPLOYMENT_REPOSITORY, credentialsId: 'bitbucket'
                                        try {
                                            String clusterURL = getEnvUrl(clusterEnv.get("cluster"))
                                            String project = clusterEnv.get("project")
                                            String cluster = clusterEnv.get("cluster")
                                            String tokenId = cluster + '-' + project + '-jenkins'
                                            String imageTagName = env.IMAGE_VERSION_TAG ?: 'IMAGE_VERSION'

                                            echo "Deployment Name: ${deploymentName}"
                                            echo "clusterEnv: ${clusterEnv}"
                                            echo "Cluster Map: ${CLUSTER_MAP}"
                                            echo "Cluster URL: ${clusterURL}"
                                            echo "TOKEN ID: ${tokenId}"
                                            echo "image version tag: ${imageTagName}"

                                            sh "find . -type f -exec sed -i s/${imageTagName}/${RELEASE_VERSION}/g {} \\;"
                                            withCredentials([string(credentialsId: "${tokenId}", variable: 'TOKEN')]) {
                                                openshift.logLevel(LOG_LEVEL)
                                                applyFile(
                                                        projectName: project,
                                                        deploymentFile: "${env.K8S_DEPLOYMENT_CONFIG}",
                                                        clusterAPI: clusterURL,
                                                        clusterToken: "${TOKEN}"
                                                )
                                            }
                                        } catch (e) {
                                            echo "Error encountered: " + e.getMessage()
                                            throw e
                                        }
                                    }
                                } else {
                                    echo "No deployment repo is specified! No deployment."
                                }
                            }
                        }
                    }
                }
            }
        }
        post {
            always {
                container('jnlp') {
                    script {
                        openshift.logLevel(0)
                        openshift.withCluster( "${OPENSHIFT_CLUSTER_URL}" ) {
                            openshift.withCredentials( "${OPENSHIFT_TOKEN}" ) {
                                openshift.withProject( "${openshiftProject}" ) {
                                    openshift.selector('buildconfig', [app: "${BUILD_NAME}"]).delete()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

def escapeBranch() {
    if (env.BRANCH_NAME =~ /release.*|develop|master/) {
        return "." + "${env.BUILD_NUMBER}"
    }
    return "-" + "${env.BRANCH_NAME}".replace('/', '-') + "." + "${env.BUILD_NUMBER}"
}

def fetchClustersFromOnboardingFile(Map<String, Map<String, String>> clustersMap) {
    List<Map<String, String>> clustersData = Helper.base64DecodeMap(env.OPENSHIFT_APP_PROJECTS)
    echo "OPENSHIFT_APP_PROJECTS: ${clustersData}"
    StringBuffer clusterNames = new StringBuffer()
    for (Map<String, String> map : clustersData) {
        StringBuffer individualName = new StringBuffer()
        for (Map.Entry<String, String> entry : map.entrySet()) {
            individualName.append(entry.getKey()).append(":").append(entry.getValue()).append(" ")
        }
        clustersMap.put(individualName.toString().trim(), map)
        clusterNames.append(individualName.toString()).append("\n")
    }
    clusterNames.append("${NO_DEPLOYMENT}")
    return clusterNames.toString()
}

def getEnvUrl(String envName) {
    def data = libraryResource "${CLUSTER_CONFIG_FILE}"
    return Helper.lookupClusterURL(data, envName)
}
======== api_promotion_pipeline.groovy ========
/**
 * Most of the following is based on info from the Jenkins blog and documentation:
 * https://jenkins.io/blog/2017/10/02/pipeline-templates-with-shared-libraries/
 * https://jenkins.io/doc/book/pipeline/shared-libraries/
 */
import com.mufg.tsi.devsecops.pipelines.Helper

class BuildConfiguration {
    def id = ""
    def baseImage = ""
    def buildOption = ""
    def packagingType = ""
}

def call(
        String openshiftCluster = "",
        String openshiftProject = "",
        String nexusCredentialId = "nexus-user-token",
		String artifactVersion="1.0.1-SNAPSHOT",
        String nexusHost="nexus.unionbank.com",
        String nexusArtifactPath="repository/maven-snapshots/com/mufg/eip/promote/api/bbk-api-promotion-sdk/1.0.1-SNAPSHOT/bbk-api-promotion-sdk-1.0.1-20200926.010307-29.jar"
       
       
        
) {

    assert openshiftCluster?.trim(): "openshift Cluster is required."
    assert openshiftProject?.trim(): "openshift Project is required."
	
    def SECURITY_QUALITY_PASS_FLAG = true
	def OPEN_LEGACY_API_PROMOTION_PASS_FLAG = true
    def USER_DOC = 'https://prod-1-confluence.mufgamericas.com/display/CICDDocs/CICD+Pipelines+User+Documentation'

    EMERGENCY_CM_APPROVERS_FILE = 'change_management/emergency_approvers.yaml'

    pipeline {
        agent {
            kubernetes {
                cloud "${openshiftCluster}-${openshiftProject}"
                label "jenkins-${openshiftCluster}-${openshiftProject}-${BUILD_NUMBER}"
                //This can't exceed 63 chars
                yaml libraryResource('agent-pod-conf/jenkins-agent-pod-api-promotion.yaml')
            }
        }
        options {
            //GLOBAL BUILD OPTIONS
            buildDiscarder(logRotator(numToKeepStr: '13'))
            disableConcurrentBuilds()
            timeout(time: 45, unit: 'MINUTES')
        }

        environment {
            NEXUS_REGISTRY_URL = "${dockerRegistryURL}"
            // This path is from a emptydir volume primarily for  multiple containers to share files
            MAVEN_ARTIFACTS = '/var/tmp/maven-artifacts'

            // These are for oc to make outbound connection to the OpenShift cluster URL
            PROXY='http://ub-app-proxy.uboc.com:80'
            HTTP_PROXY="${env.PROXY}"
            http_proxy="${env.PROXY}"
            https_proxy="${env.PROXY}"
            HTTPS_PROXY="${env.PROXY}"
            NO_PROXY=".eip-apps-aws-prd-east.opn.unionbank.com, .eip-apps-aws-prd-west.opn.unionbank.com, nexus-dev.unionbank.com, nexus.unionbank.com"
        }

		parameters {
			choice(name: "Source", choices: ['SBX', 'DEV', 'SIT', 'PTE', 'TST', 'PRD', 'CDR'], description: "Choose the Source environment to export the API.")
			choice(name: "Target", choices: ['SBX', 'DEV', 'SIT', 'PTE', 'TST', 'PRD', 'CDR'], description: "Choose the Target environment to import the API.")	
			string(name: 'ChangeNumber', defaultValue: '', description: 'Change Management Ticket Number - e.g. CHG1234567. This must be provided for production deployment or the deployment will be denied. For emergency deployment, i.e. in case an emergency change management ticket can\'t be obtained, enter \"emergency\" and reach out to the pipeline operations team for approval.')
		}
		
		
        stages {
        
        	stage('Change Management Validation') {
                when {
                  allOf {
                    expression {SECURITY_QUALITY_PASS_FLAG == true}
                  }
                }
                steps {
                   container('python3') {
                       script {
                       		echo "In Change Management Validation Step"
                               echo "Source Env Selected: ${params.Source}"
                               echo "Target Env Selected: ${params.Target}"
                               String environment = "${params.Target}"

                               if ("${environment}" == "PRD" || "${environment}" == "CDR") {
                                   // if ticket number = emergency, allow for emergency deployment by the pipeline operations team
                                   if ( params.ChangeNumber.trim().toLowerCase() == "emergency") {
                                       echo "########## IMPORTANT ##########"
                                       echo "This is an emergency deployment to Production. Explicit approval by the pipeline operations team is required. Please have the operations team work alongside you to approve the deployment."
                                       echo "Emergency deployment should be a last resort to resolve a pressing Production outage in case an emergency change management ticket can't be obtained. A post-event change management ticket must be raised to cover this emergency change."
                                       def userInput = ''
                                       timeout(time: 15, unit: "MINUTES") {
                                           userInput = input(message: 'Pipeline Operator: Do you approve this emergency deployment to Production?', 
                                                             ok: 'Approve',
                                                             submitter: getEmergencyCMApprovers(),
                                                             parameters: [
                                                             [$class: 'TextParameterDefinition', 
                                                              defaultValue: '', 
                                                              description: 'Enter "Approved" literally to approve or the deployment will be aborted.', 
                                                              name: '']
                                                             ])
                                       } 
                                       if (userInput.trim().toLowerCase() != "approved") { 
										   OPEN_LEGACY_API_PROMOTION_PASS_FLAG = false
                                           error "Invalid value for approving emergency deployment. Deployment aborted."
				
                                       }
                                       echo "########## IMPORTANT ##########"
                                       echo "Emergency deployment approved. Proceed to deployment."
                                   } else {
                                       echo "This is a Production Deployment. Validate Change Ticket with CM (ServiceNow)."
                                       try {
                                           validateChangeManagementTicket(
                                               gsiCode: "${env.GSI}",
                                               changeNumber: "${params.ChangeNumber}"
                                           )
                                       //TO-DO - validate cluster/project with labels matching environment
                                       } catch (e) {
                                           //currentBuild.result = "FAILURE"
										   OPEN_LEGACY_API_PROMOTION_PASS_FLAG = false
                                           error "Change Management Validation Failed. See logs for more details.\n${e}"
                                       }
                                   } 
                               } else {
                                   echo "Change Management is not required for ${environment} environment."
                               }
                       }
                   }
                }
            }
            stage("Prepare WSO2 Env") {
                steps {
                    container('wso2') {
                        script {
                            def fileName = 'prepare-env.sh'
                            def command = libraryResource "api-promotion/scripts/${fileName}"
                            writeFile(file: fileName, text: command, encoding: "UTF-8")
                            sh "chmod u+x ${fileName}"
                            withCredentials([
                                usernamePassword(credentialsId: 'api-promotion-epam-nonprd', 
                                                 usernameVariable: 'EPAM_NONPRD_USER', 
                                                 passwordVariable: 'EPAM_NONPRD_PASSWORD'),
                                usernamePassword(credentialsId: 'api-promotion-epam-prd', 
                                                 usernameVariable: 'EPAM_PRD_USER', 
                                                 passwordVariable: 'EPAM_PRD_PASSWORD')
                            ]) {
                                    sh "./prepare-env.sh ${params.Source} ${params.Target}"
                            }
                        }
                    }
                }
            }
			stage("WSO2 API Promotion") {
				steps {
					container('wso2'){
						catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
							
								script {
								
									echo "Reading json promotion config file for api's to be promoted"
									def apiName
									def apiVersion
									def sourceEndpointUrl
									def targetEndpointUrl
									def promotionConfig
									def branchName = ("${params.Target}" == "PRD") ? "master" : ("${params.Target}".toLowerCase())
									echo "BBK_REPO_URL: ${env.BBK_REPO_URL}"
									echo "branchName: ${branchName}"
									
									git branch: branchName, url: env.BBK_REPO_URL, credentialsId: 'bitbucket'
									
									promotionConfig = readJSON file: 'config/promotion.json'
                   
									promotionConfig.promotions.each { item ->
										apiName = "$item.apiName"
										apiVersion = "$item.apiVersion"
										sourceEndpointUrl = "$item.sourceEndpointUrl"
										targetEndpointUrl = "$item.targetEndpointUrl"
										script {
											def fileName = 'promote-api.sh'
											def command = libraryResource "api-promotion/scripts/${fileName}"
											writeFile(file: fileName, text: command, encoding: "UTF-8")
											sh "chmod u+x ${fileName}"
											sh "./promote-api.sh ${params.Source} ${params.Target} ${apiName} ${apiVersion} ${sourceEndpointUrl} ${targetEndpointUrl}"
										}
									}
								} 
						}
					}
				}
			}
			stage("WSO2 APP Promotion") {
						steps {
							container('wso2') {
								script {
									def fileName = 'promote-app.sh'
									def command = libraryResource "api-promotion/scripts/${fileName}"
									writeFile(file: fileName, text: command, encoding: "UTF-8")
									sh "chmod u+x ${fileName}"
                                    sh "./promote-app.sh ${params.Source} ${params.Target}"
								}
							}
						}
					
			}
			stage ("Openlegacy API Promotion") {
				when {
                  allOf {
                    expression {OPEN_LEGACY_API_PROMOTION_PASS_FLAG == true}
                  }
                }
				steps {
					container('maven'){
						script {
							echo "Openlegacy promotion starts - "
							echo "Trying to read json config file."
							echo "${PWD}"
							sh 'ls -R'

							def apiName
							def apiVersion
							def sourceEndpointUrl
							def targetEndpointUrl
							def openlegacyPromoteGatewayBaseUrls
							def openlegacyPromoteEndpointIds
							def promotionConfig = readJSON file: 'config/promotion.json'
                            def artifactName="bbk-api-promotion-sdk-${artifactVersion}.jar"  
                            def nexusArtifact="https://${nexusHost}/${nexusArtifactPath}"
                            def downloadFolder ="${MAVEN_ARTIFACTS}/promote"
                            def downloadedArtifactName= "${downloadFolder}/${artifactName}"
							sourceEnv = "${params.Source}"
							targetEnv = "${params.Target}"
							promotionConfig.promotions.each { item ->
								apiName = "$item.apiName"
								apiVersion = "$item.apiVersion"
								sourceEndpointUrl = "$item.sourceEndpointUrl"
								targetEndpointUrl = "$item.targetEndpointUrl"
								openlegacyPromoteGatewayBaseUrls="$item.openlegacyPromoteGatewayBaseUrls"
								openlegacyPromoteEndpointIds="$item.openlegacyPromoteEndpointIds"
								if ( openlegacyPromoteGatewayBaseUrls &&  openlegacyPromoteGatewayBaseUrls !="null") {
                                  script {
                                      withCredentials([
                                          usernamePassword(credentialsId: "${nexusCredentialId}",
                                                   usernameVariable: 'NEXUS_USERNAME',
                                                   passwordVariable: 'NEXUS_PASSWORD')]) {
                                          sh (script: """
                                              export http_proxy=''
                                              mkdir -p ${downloadFolder}
                                              curl -k -v -u ${NEXUS_USERNAME}:${NEXUS_PASSWORD} --output ${downloadedArtifactName} ${nexusArtifact}
                                              java -Dopenlegacy.promote.env.from=${sourceEnv} -Dopenlegacy.promote.env.to=${targetEnv}  -Dopenlegacy.promote.endpointIds=${openlegacyPromoteEndpointIds} -Dopenlegacy.promote.gateway.baseUrls=${openlegacyPromoteGatewayBaseUrls}  -Dopenlegacy.ignore.pathCheck=true -Dopenlegacy.ignore.promote.rules=true   -Dopenlegacy.api.name=${apiName}  -Dopenlegacy.api.version=${apiVersion} -jar ${downloadedArtifactName} 
                                          """, returnStdout: true).trim()
                                           }
                                      }
         
								} else {
									echo "Skipping - openlegacy promotion for apiName ${apiName} version ${apiVersion} as it is not configured in promotion.json"
								}
							}
						} 
					}
				}
			}
        }
        post {
            success { 
                 script {
                     if ("${environment}" == "PRD" || "${environment}" == "CDR") {
                         // Preserve build for successful Production deployment
                         currentBuild.keepLog = true
                     }
                 }
            }
        }
    }
}

def escapeBranch() {
    if (env.BRANCH_NAME =~ /release.*|develop|master/) {
        return "." + "${env.BUILD_NUMBER}"
    }
    return "-" + "${env.BRANCH_NAME}".replace('/', '-') + "." + "${env.BUILD_NUMBER}"
}

def fetchAllowedSourceEnvironments() {
    StringBuffer allowedSourceEnv = new StringBuffer()
    if ("${env.BRANCH_NAME}" == "master") {
		allowedSourceEnv.append("CDR")
	}
	else if ("${env.BRANCH_NAME}" =~ /release.*/) {
		allowedSourceEnv.append("DEV").append("\n").append("UAT")
	}
	else {
		allowedSourceEnv.append("SBX").append("\n").append("DEV").append("\n").append("SIT").append("\n").append("TST").append("\n").append("PTE")
	}
	return allowedSourceEnv.toString()
    
}

def fetchAllowedTargetEnvironments(){
	StringBuffer allowedTargetEnv = new StringBuffer()
	if ("${env.BRANCH_NAME}" == "master") {
		allowedTargetEnv.append("PRD")
	}
	else if ("${env.BRANCH_NAME}" =~ /release.*/) {
		allowedTargetEnv.append("CDR")
	}
	else {
		allowedTargetEnv.append("SBX").append("\n").append("DEV").append("\n").append("SIT").append("\n").append("TST").append("\n").append("PTE")
	}
	return allowedTargetEnv.toString()
}

def getEmergencyCMApprovers() {
    def data = libraryResource "${EMERGENCY_CM_APPROVERS_FILE}"
    return Helper.getEmergencyCmApprovers(data)
}
======== api_promotion_wso2_v32_pipeline.groovy ========
/**
 * Most of the following is based on info from the Jenkins blog and documentation:
 * https://jenkins.io/blog/2017/10/02/pipeline-templates-with-shared-libraries/
 * https://jenkins.io/doc/book/pipeline/shared-libraries/
 */
import com.mufg.tsi.devsecops.pipelines.Helper

class BuildConfiguration {
    def id = ""
    def baseImage = ""
    def buildOption = ""
    def packagingType = ""
}

def call(
        String openshiftCluster = "",
        String openshiftProject = "",
        String nexusCredentialId = "nexus-user-token",
		String artifactVersion="1.0.1",
        String nexusHost="nexus.unionbank.com",
        String nexusArtifactPath="repository/maven-releases/com/mufg/eip/promote/api/bbk-api-promotion-sdk/1.0.1/bbk-api-promotion-sdk-1.0.1.jar"
       
       
        
) {

    assert openshiftCluster?.trim(): "openshift Cluster is required."
    assert openshiftProject?.trim(): "openshift Project is required."
	
    def SECURITY_QUALITY_PASS_FLAG = true
	def OPEN_LEGACY_API_PROMOTION_PASS_FLAG = true
    def USER_DOC = 'https://prod-1-confluence.mufgamericas.com/display/CICDDocs/CICD+Pipelines+User+Documentation'
    def continueWithApiPromotion = false
    EMERGENCY_CM_APPROVERS_FILE = 'change_management/emergency_approvers.yaml'

    pipeline {
        agent {
            kubernetes {
                cloud "${openshiftCluster}-${openshiftProject}"
                label "jenkins-${openshiftCluster}-${openshiftProject}-${BUILD_NUMBER}"
                //This can't exceed 63 chars
                yaml libraryResource('agent-pod-conf/jenkins-agent-pod-api-promotion.yaml')
            }
        }
        options {
            //GLOBAL BUILD OPTIONS
            buildDiscarder(logRotator(numToKeepStr: '13'))
            disableConcurrentBuilds()
            timeout(time: 45, unit: 'MINUTES')
        }

        environment {
            NEXUS_REGISTRY_URL = "${dockerRegistryURL}"
            // This path is from a emptydir volume primarily for  multiple containers to share files
            MAVEN_ARTIFACTS = '/var/tmp/maven-artifacts'

            // These are for oc to make outbound connection to the OpenShift cluster URL
            PROXY='http://ub-app-proxy.uboc.com:80'
            HTTP_PROXY="${env.PROXY}"
            http_proxy="${env.PROXY}"
            https_proxy="${env.PROXY}"
            HTTPS_PROXY="${env.PROXY}"
            NO_PROXY=".eip-apps-aws-prd-east.opn.unionbank.com, .eip-apps-aws-prd-west.opn.unionbank.com, nexus-dev.unionbank.com, nexus.unionbank.com, .eip-apps-aws-uat.opn.unionbank.com"
        }

		parameters {
			choice(name: "Source", choices: ['SBX', 'DEV', 'SIT', 'TST', 'PTE', 'UAT', 'PRD', 'CDR'], description: "Choose the Source environment to export the API.")
			choice(name: "Target", choices: ['SBX', 'DEV', 'SIT', 'TST', 'PTE', 'UAT', 'PRD', 'CDR'], description: "Choose the Target environment to import the API.")
			string(name: 'ChangeNumber', defaultValue: '', description: 'Change Management Ticket Number - e.g. CHG1234567. This must be provided for production deployment or the deployment will be denied. For emergency deployment, i.e. in case an emergency change management ticket can\'t be obtained, enter \"emergency\" and reach out to the pipeline operations team for approval.')
		}
		
		
        stages {
        	stage('Change Management Validation') {
                when {
                  allOf {
                    expression {SECURITY_QUALITY_PASS_FLAG == true}
                  }
                }
                steps {
                   container('python3') {
                       script {
                       		echo "In Change Management Validation Step"
                               echo "Source Env Selected: ${params.Source}"
                               echo "Target Env Selected: ${params.Target}"
                               String environment = "${params.Target}"

                               if ("${environment}" == "PRD" || "${environment}" == "CDR") {
                                   // if ticket number = emergency, allow for emergency deployment by the pipeline operations team
                                   if ( params.ChangeNumber.trim().toLowerCase() == "emergency") {
                                       echo "########## IMPORTANT ##########"
                                       echo "This is an emergency deployment to Production. Explicit approval by the pipeline operations team is required. Please have the operations team work alongside you to approve the deployment."
                                       echo "Emergency deployment should be a last resort to resolve a pressing Production outage in case an emergency change management ticket can't be obtained. A post-event change management ticket must be raised to cover this emergency change."
                                       def userInput = ''
                                       timeout(time: 15, unit: "MINUTES") {
                                           userInput = input(message: 'Pipeline Operator: Do you approve this emergency deployment to Production?', 
                                                             ok: 'Approve',
                                                             submitter: getEmergencyCMApprovers(),
                                                             parameters: [
                                                             [$class: 'TextParameterDefinition', 
                                                              defaultValue: '', 
                                                              description: 'Enter "Approved" literally to approve or the deployment will be aborted.', 
                                                              name: '']
                                                             ])
                                       } 
                                       if (userInput.trim().toLowerCase() != "approved") { 
										   OPEN_LEGACY_API_PROMOTION_PASS_FLAG = false
                                           error "Invalid value for approving emergency deployment. Deployment aborted."
				
                                       }
                                       echo "########## IMPORTANT ##########"
                                       echo "Emergency deployment approved. Proceed to deployment."
                                   } else {
                                       echo "This is a Production Deployment. Validate Change Ticket with CM (ServiceNow)."
                                       try {
                                           validateChangeManagementTicket(
                                               gsiCode: "${env.GSI}",
                                               changeNumber: "${params.ChangeNumber}"
                                           )
                                       //TO-DO - validate cluster/project with labels matching environment
                                       } catch (e) {
                                           //currentBuild.result = "FAILURE"
										   OPEN_LEGACY_API_PROMOTION_PASS_FLAG = false
                                           error "Change Management Validation Failed. See logs for more details.\n${e}"
                                       }
                                   } 
                               } else {
                                   echo "Change Management is not required for ${environment} environment."
                               }
                       }
                   }
                }
            }
            stage("Prepare WSO2 Env") {
                when {
                    allOf {
                        expression {
                            params.Source != params.Target
                        }
                    }
                }
                steps {
                    container('wso32') {
                        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                            script {
                                def fileName = 'prepare-env.sh'
                                def command = libraryResource "api-promotion-new/scripts/${fileName}"
                                writeFile(file: fileName, text: command, encoding: "UTF-8")
                                sh "chmod u+x ${fileName}"
                                withCredentials([
                                        usernamePassword(credentialsId: 'api-promotion-epam-nonprd',
                                                usernameVariable: 'EPAM_NONPRD_USER',
                                                passwordVariable: 'EPAM_NONPRD_PASSWORD'),
                                        usernamePassword(credentialsId: 'api-promotion-epam-prd',
                                                usernameVariable: 'EPAM_PRD_USER',
                                                passwordVariable: 'EPAM_PRD_PASSWORD')
                                ]) {
                                    try {
                                        sh "./prepare-env.sh ${params.Source} ${params.Target}"
                                        continueWithApiPromotion = true
                                    }
                                    catch (Exception e) {
                                        echo "ERROR: Exception While preparing environment for API Promotion process"
                                        continueWithApiPromotion = false
                                    }
                                }
                            }
                        }
                    }
                }
            }
            stage("WSO2 API Promotion") {
                when {
                    expression {
                        continueWithApiPromotion
                    }
                }
                steps {
                    container('wso32'){
                        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
							
                            script {
                                echo "Reading json promotion config file for api's to be promoted"
                                def apiName
                                def apiVersion
                                def sourceEndpointUrl
                                def targetEndpointUrl
                                def promotionConfig
                                def branchName = ("${params.Target}" == "PRD") ? "master" : ("${params.Target}".toLowerCase())
                                echo "BBK_REPO_URL: ${env.BBK_REPO_URL}"
                                echo "branchName: ${branchName}"

                                git branch: branchName, url: env.BBK_REPO_URL, credentialsId: 'bitbucket'

                                promotionConfig = readJSON file: 'config/promotion.json'

                                promotionConfig.promotions.each { item ->
                                    apiName = "$item.apiName"
                                    apiVersion = "$item.apiVersion"
                                    sourceEndpointUrl = "$item.sourceEndpointUrl"
                                    targetEndpointUrl = "$item.targetEndpointUrl"
                                    script {
                                        //Validation checks are done against "null" string intentionally, as per debug analysis
                                        if(sourceEndpointUrl != "null"  && targetEndpointUrl != "null"){
                                            def fileName = 'promote-api.sh'
                                            def command = libraryResource "api-promotion-new/scripts/${fileName}"
                                            writeFile(file: fileName, text: command, encoding: "UTF-8")
                                            sh "chmod u+x ${fileName}"
                                            sh "./promote-api.sh ${params.Source} ${params.Target} ${apiName} ${apiVersion} ${sourceEndpointUrl} ${targetEndpointUrl}"
                                        } else {
                                            echo "ERROR: Skipping API Promotion as there are no sourceEndpointURL and targetEndpointURL attributes configured in promotion.json"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            stage("WSO2 APP Promotion") {
                when {
                    expression {
                        continueWithApiPromotion
                    }
                }
                steps {
                    container('wso32') {
                        script {
                            def fileName = 'promote-app.sh'
                            def command = libraryResource "api-promotion-new/scripts/${fileName}"
                            writeFile(file: fileName, text: command, encoding: "UTF-8")
                            sh "chmod u+x ${fileName}"
                            sh "./promote-app.sh ${params.Source} ${params.Target}"
                        }
                    }
                }

            }
            stage ("Openlegacy API Promotion") {
                when {
                    allOf {
                        expression {OPEN_LEGACY_API_PROMOTION_PASS_FLAG == true}
                    }
                }
                steps {
                    container('maven'){
                        script {
                            echo "Openlegacy promotion starts - "
                            echo "Trying to read json config file."
                            echo "${PWD}"
                            sh 'ls -R'

                            def apiName
                            def apiVersion
                            def sourceEndpointUrl
                            def targetEndpointUrl
                            def openlegacyPromoteGatewayBaseUrls
                            def openlegacyPromoteEndpointIds
                            def promotionConfig = readJSON file: 'config/promotion.json'
                            def artifactName="bbk-api-promotion-sdk-${artifactVersion}.jar"  
                            def nexusArtifact="https://${nexusHost}/${nexusArtifactPath}"
                            def downloadFolder ="${MAVEN_ARTIFACTS}/promote"
                            def downloadedArtifactName= "${downloadFolder}/${artifactName}"
                            sourceEnv = "${params.Source}"
                            targetEnv = "${params.Target}"
                            promotionConfig.promotions.each { item ->
                                apiName = "$item.apiName"
                                apiVersion = "$item.apiVersion"
                                sourceEndpointUrl = "$item.sourceEndpointUrl"
                                targetEndpointUrl = "$item.targetEndpointUrl"
                                openlegacyPromoteGatewayBaseUrls="$item.openlegacyPromoteGatewayBaseUrls"
                                openlegacyPromoteEndpointIds="$item.openlegacyPromoteEndpointIds"

                                script {
                                    if (openlegacyPromoteGatewayBaseUrls != "null"  &&  openlegacyPromoteGatewayBaseUrls != "null") {
                                        withCredentials([
                                                usernamePassword(credentialsId: "${nexusCredentialId}",
                                                        usernameVariable: 'NEXUS_USERNAME',
                                                        passwordVariable: 'NEXUS_PASSWORD')]) {
                                            sh (script: """
                                              export http_proxy=''
                                              mkdir -p ${downloadFolder}
                                              curl -k -v -u ${NEXUS_USERNAME}:${NEXUS_PASSWORD} --output ${downloadedArtifactName} ${nexusArtifact}
                                              java -Dopenlegacy.promote.env.from=${sourceEnv} -Dopenlegacy.promote.env.to=${targetEnv}  -Dopenlegacy.promote.endpointIds=${openlegacyPromoteEndpointIds} -Dopenlegacy.promote.gateway.baseUrls=${openlegacyPromoteGatewayBaseUrls}  -Dopenlegacy.ignore.pathCheck=true -Dopenlegacy.ignore.promote.rules=true   -Dopenlegacy.api.name=${apiName}  -Dopenlegacy.api.version=${apiVersion} -jar ${downloadedArtifactName} 
                                          """, returnStdout: true).trim()
                                        }
                                    }
                                    else {
                                        echo "Skipping - openlegacy promotion for apiName ${apiName} version ${apiVersion} as it is not configured in promotion.json"
                                    }

                                }
                            }
                        }
                    }
                }
            }
        }
        post {
            success { 
                script {
                    String environment = "${params.Target}"
                    if ("${environment}" == "PRD" || "${environment}" == "CDR") {
                        // Preserve build for successful Production deployment
                        currentBuild.keepLog = true
                    }
                }
            }
        }
    }
}

def escapeBranch() {
    if (env.BRANCH_NAME =~ /release.*|develop|master/) {
        return "." + "${env.BUILD_NUMBER}"
    }
    return "-" + "${env.BRANCH_NAME}".replace('/', '-') + "." + "${env.BUILD_NUMBER}"
}

def fetchAllowedSourceEnvironments() {
    StringBuffer allowedSourceEnv = new StringBuffer()
    if ("${env.BRANCH_NAME}" == "master") {
        allowedSourceEnv.append("CDR")
    }
    else if ("${env.BRANCH_NAME}" =~ /release.*/) {
        allowedSourceEnv.append("DEV").append("\n").append("UAT")
    }
    else {
        allowedSourceEnv.append("SBX").append("\n").append("DEV").append("\n").append("SIT").append("\n").append("TST").append("\n").append("PTE")
    }
    return allowedSourceEnv.toString()

}

def fetchAllowedTargetEnvironments(){
    StringBuffer allowedTargetEnv = new StringBuffer()
    if ("${env.BRANCH_NAME}" == "master") {
        allowedTargetEnv.append("PRD")
    }
    else if ("${env.BRANCH_NAME}" =~ /release.*/) {
        allowedTargetEnv.append("CDR")
    }
    else {
        allowedTargetEnv.append("SBX").append("\n").append("DEV").append("\n").append("SIT").append("\n").append("TST").append("\n").append("PTE")
    }
    return allowedTargetEnv.toString()
}

def getEmergencyCMApprovers() {
    def data = libraryResource "${EMERGENCY_CM_APPROVERS_FILE}"
    return Helper.getEmergencyCmApprovers(data)
}
======== applyFile.groovy ========
#!/usr/bin/env groovy

class ApplyFileInput implements Serializable {
    String deploymentFile

    //Optional - Platform
    String clusterUrl = ""
    String clusterAPI = ""
    String clusterToken = ""
    String projectName = ""
}

def call(Map input) {
    call(new ApplyFileInput(input))
}

def call(ApplyFileInput input) {
    if (input.clusterUrl?.trim().length() > 0) {
        echo "WARNING: clusterUrl is deprecated. Please use 'clusterAPI'"
        input.clusterAPI = input.clusterUrl
    }

    def FILES_LIST = sh(script: "find '${input.deploymentFile}' -type f | sort", returnStdout: true).trim()
    echo "FILES_LIST : ${FILES_LIST}"
    for (String fileName : FILES_LIST.split("\\r?\\n")) {
        openshift.withCluster(input.clusterAPI, "\\\"${input.clusterToken}\\\"") {
            openshift.withProject(input.projectName) {
                def applyResult
                try {
                    applyResult = openshift.apply(readFile(fileName))
                }
                catch (Exception e) {
                    echo "Apply file failed: ${fileName}"
                    throw e
                }
                echo "Deployments: ${applyResult.out}"
            }
        }
    }
}
======== applyFile.txt ========
# applyFile

This function processes any file without parameters file  "oc apply"

Sample usage:

To run against the local:
```
stage {
    steps{
        applyFile(projectName: "example", deploymentFile: "hello-world-deployment.yaml")
    }
}
```
Run against remote cluster:
```
stage {
    steps{
        applyFile(projectName: "example", deploymentFile: "hello-world-deployment.yaml", clusterUrl: "https://master.example.com", clusterToken: "KUBERNETES TOKEN")
    }
}
```
======== applyTemplate.groovy ========
#!/usr/bin/env groovy

class ApplyTemplateInput implements Serializable{
    String templateFile
    String parameterFile

    //Optional - Platform
    String clusterUrl = ""
    String clusterAPI = ""
    String clusterToken = ""
    String projectName = ""
}

def call(Map input) {
    call(new ApplyTemplateInput(input))
} 

def call(ApplyTemplateInput input) {
    if (input.clusterUrl?.trim().length() > 0) {
        echo "WARNING: clusterUrl is deprecated. Please use 'clusterAPI'"

        input.clusterAPI = input.clusterUrl
    }

    openshift.withCluster(input.clusterUrl, input.clusterToken) {
        openshift.withProject(input.projectName) {
            def fileNameArg = input.templateFile.toLowerCase().startsWith("http") ? input.templateFile : "--filename=${input.templateFile}"
            def parameterFileArg = input.parameterFile?.trim()?.length() <= 0 ? "" : "--param-file=${input.parameterFile}"

            def models = openshift.process(fileNameArg, parameterFileArg, "--ignore-unknown-parameters")
            echo "Creating this template will instantiate ${models.size()} objects"

            // We need to find DeploymentConfig definitions inside
            // So iterating trough all the objects loaded from the Template
            for ( o in models ) {
                if (o.kind == "DeploymentConfig") {
                    // The bug in OCP 3.7 is that when applying DC the "Image" can't be undefined
                    // But when using automatic triggers it updates the value for this on runtime
                    // So when applying this dynamic value gets overwriten and breaks deployments

                    // We will check if this DeploymentConfig already pre-exists and fetch the current value of Image
                    // And set this Image -value into the DeploymentConfig template we are applying
                    def dcSelector = openshift.selector("deploymentconfig/${o.metadata.name}")
                    def foundObjects = dcSelector.exists()
                    if (foundObjects) {
                        echo "This DC exists, copying the image value"
                        def dcObjs = dcSelector.objects()
                        echo "Image now: ${dcObjs[0].spec.template.spec.containers[0].image}"
                        o.spec.template.spec.containers[0].image = dcObjs[0].spec.template.spec.containers[0].image
                    }
                }
            }
            def created = openshift.apply( models )
            echo "Created: ${created.names()}"
        }
    }
}
======== applyTemplate.txt ========
# applyTemplate

This function processes template file with parameters file and then runs "oc apply" for the processing results.
Also it has a workaround for the bug that if the DeploymentConfig already exists, it will break the Image Trigger.

Sample usage:

To run against the local:
```
stage {
    steps{
        applyTemplate(projectName: "example", templateFile: "hello-world-template.yaml", parameterFile: "hello-world-params.txt")
    }
}
```
Run against remote cluster:
```
stage {
    steps{
        applyTemplate(projectName: "example", templateFile: "hello-world-template.yaml", parameterFile: "hello-world-params.txt", clusterUrl: "https://master.example.com", clusterToken: "KUBERNETES TOKEN")
    }
}
```

Include this library by adding this before "pipeline" in your Jenkins:
```
library identifier: "pipeline-library@master", retriever: modernSCM(
  [$class: "GitSCMSource",
   remote: "https://github.com/redhat-cop/pipeline-library.git"])
```======== aws_pipeline.groovy ========
/**
  * Most of the following is based on info from the Jenkins blog and documentation: 
  * https://jenkins.io/blog/2017/10/02/pipeline-templates-with-shared-libraries/
  * https://jenkins.io/doc/book/pipeline/shared-libraries/
  * TODO: Evaluate if we should assume role on master so we don't pass any secrets to workers
  */
def call(body) {
    def pipelineParams = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    pipeline {
        agent {
    	    kubernetes {
                cloud "${pipelineParams.openshiftCluster}-${pipelineParams.openshiftProject}"
    	        label "${JOB_BASE_NAME}-${BUILD_NUMBER}"    //This can't exceed 63 chars
                yamlFile 'pipelines/agent/jenkins-agent-pod.yaml'
                //yamlFile 'pipelines/aws-agent-pod.yaml'
    	    }
        }

        environment {
            HTTP_PROXY="http://ub-app-proxy.uboc.com:80"
            HTTPS_PROXY="http://ub-app-proxy.uboc.com:80"
            NO_PROXY=".btmna.com,.cluster.local,.svc,.unionbank.com,docker-registry-default.opn-apps-mz-dev.unionbank.com,localhost"
            AWS_DEFAULT_REGION="us-west-2"
        }

        stages {
            stage('Provision') {
                steps {
                    container('python-aws') {
                        echo "Starting Provisioning"
                        dir('awsdeploy') {
                            git url: "http://n313004@bbk.unionbank.com/scm/~n313004/awsdeploy.git", credentialsId: "mike-bbk"
                        }
                        withCredentials([usernamePassword(credentialsId: 'mike-ad', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                            sh ". /venv/bin/activate; python3.7 /iac/source/aws_automation/mufg_cli.py --deploy_file awsdeploy/deploy.yaml --username $USERNAME --password $PASSWORD"
                        }
                        echo "Ending Provisioning"
                    }
                }
            }
            stage('Validation') {
                steps {
                    echo "Validation not currently implemented, check AWS for success/failure of cloudformation"
                }
            }
        }
    }
}
======== build_pipeline.groovy ========
/**
 * Most of the following is based on info from the Jenkins blog and documentation:
 * https://jenkins.io/blog/2017/10/02/pipeline-templates-with-shared-libraries/
 * https://jenkins.io/doc/book/pipeline/shared-libraries/
 */
def call(body) {
    def pipelineParams = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    def APP_NAME = ''
    def BUILD_NAME = ''
    def BASE_IMAGE = ''
    def PLATFORM = ''
    def ARTIFACT_FILE = ''
    def pom = ''
    def USER_DOC = 'https://prod-1-confluence.mufgamericas.com/display/CICDDocs/CICD+Pipelines+User+Documentation'
    def SECURITY_QUALITY_PASS_FLAG = true
    def pomQualityCheckOverrideProperties = [
      		'cpd.skip',
            'checkstyle.skip',
      		'pmd.skip',
      		'skip.pmd.check',
      		'maven.test.skip',
        ]
  
    pipeline {
        agent {
            kubernetes {
                cloud "${pipelineParams.openshiftCluster}-${pipelineParams.openshiftProject}"
                label "jenkins-${JOB_BASE_NAME}-${BUILD_NUMBER}"    //This can't exceed 63 chars
                yamlFile 'pipelines/agent/jenkins-agent-pod.yaml'
            }
        }

        options {
            //GLOBAL BUILD OPTIONS
            timeout(time: 45, unit: 'MINUTES')
        }

        environment {
            NEXUS_REGISTRY_URL = "${pipelineParams.dockerRegistryURL}"

            OPENSHIFT_CLUSTER_URL = "insecure://kubernetes.default.svc:443"
            OPENSHIFT_TOKEN = credentials("${pipelineParams.openshiftCluster}-${pipelineParams.openshiftProject}-jenkins")

            // This path is from a emptydir volume primarily for  multiple containers to share files
            MAVEN_ARTIFACTS = '/var/tmp/maven-artifacts'
            // Per https://access.redhat.com/documentation/en-us/red_hat_jboss_web_server/3.1/html/red_hat_jboss_web_server_for_openshift/jws_on_openshift_get_started
            S2I_DEPLOYMENTS_DIR = "${MAVEN_ARTIFACTS}/deployments"
        }

        stages {
            stage('Checkout Application Repo') {
                steps {
                    container('maven') {
                        sh "mkdir mvn-build"
                        dir("mvn-build") {

                            // BBK_REPO_URL is propagated from the groovy script that create the build job
                            git branch: 'master',
                                    url: "${BBK_REPO_URL}",
                                    credentialsId: 'bitbucket'

                            script {
                                // TO-DO - Add catch FileNotFound error in case when pom.xml is not at root of repo
                                pom = readMavenPom file: 'pom.xml'

                                try {
                                    assert pom.parent.artifactId != null
                                    assert pom.parent.version != null
                                    assert pom.properties['gsi-code'] != null
                                    assert pom.properties['component-type'] != null
                                    assert pom.properties['revision'] != null
                                    assert pom.properties['changelist'] != null
                                } catch (AssertionError e) {
                                    echo "Invalid pom.xml - See details at https://prod-1-confluence.mufgamericas.com/pages/viewpage.action?spaceKey=CICDDocs&title=CICD+Pipelines+User+Documentation#CICDPipelinesUserDocumentation-PrerequisitesPrerequisites"
                                    currentBuild.result = 'FAILURE'
                                    throw e
                                }

                                try{
									for (property in pomQualityCheckOverrideProperties) {
                                      overrideValue = pom.properties[property];                                     
                                      if (overrideValue != null) {
                                        echo "${property}: ${overrideValue}";
                                        assert !overrideValue.toBoolean();
                                      }
                                   }                                 
                                   assert pom.properties['maven.pmd.maxAllowedViolations'] == null                                 
                                } catch (AssertionError e) {
                                   echo "Invalid POM Detected - Evil No Bad – Don’t turn off quality checks, this is against Bank Procedures - See details at ${USER_DOC}"
                                   currentBuild.result = 'FAILURE'
                                   throw e
                                }
                              
                                if (env.BASE_IMAGE) {
                                    echo " BASE IMAGE from configuration input"
                                    BASE_IMAGE = "${env.BASE_IMAGE}"
                                } else {
                                    echo " BASE IMAGE from jenkins file"
                                    if ((pom.parent.artifactId).contains('tomcat') && (pom.parent.version).contains('jws')) {
                                        PLATFORM = 'tomcat'
                                        BASE_IMAGE = "${pipelineParams.dockerRegistryURL}/${pipelineParams.tomcatBaseImage}"
                                    } else { //if ((pom.parent.artifactId).contains('jboss')) {
                                        PLATFORM = 'jboss'
                                        BASE_IMAGE = "${pipelineParams.dockerRegistryURL}/${pipelineParams.jbossBaseImage}"
                                    }
                                }
                                // Determine the type of build for activating profile at the parent pom
                                if (("${pom.properties['changelist']}").contains('SNAPSHOT')) {
                                    BUILD_TYPE = 'snapshot'
                                } else {
                                    BUILD_TYPE = 'release'
                                }
                                CHANGE_LIST = "-Dchangelist=${pom.properties['changelist']}"

                                // Set up ENV for querydeploy.sh to consume
                                env.POM_GROUPID = pom.groupId
                                env.POM_ARTIFACTID = pom.artifactId
                                env.POM_VERSION = sh(script: "mvn ${CHANGE_LIST} help:evaluate -Dexpression=project.version -DforceStdout -q -e",
                                        returnStdout: true
                                ).trim()
                                //pom.properties is a hash
                                // filename naming convention is taken from the parent/enterprise pom
                                env.POM_FINALNAME = "ub-${pom.properties['gsi-code']}-${pom.artifactId}-${pom.properties['component-type']}-${env.POM_VERSION}"
                                ARTIFACT_FILE = "${env.POM_FINALNAME}.${pom.packaging}"
                                APP_NAME = ("${pom.properties['gsi-code']}-${pom.artifactId}-${pom.properties['component-type']}".toLowerCase()).replace(".", "-")

                                BUILD_NAME = "${APP_NAME}-${BUILD_NUMBER}"
                            }
                        }
                    }
                }
            }
            stage('Maven Build') {
                steps {
                    container('maven') {
                        dir("mvn-build") {

                            // Run Maven build
                            sh "mvn ${CHANGE_LIST} clean install " +
                                    "-P ${PLATFORM},${BUILD_TYPE}"
                            // PLATFORM maps to a profile defined in settings.xml in config map
                            // BUILD_TYPE maps to a profile defined in parent pom

                            // Create the S2I build file system structure
                            sh "mkdir ${S2I_DEPLOYMENTS_DIR}"

                            // Copy artifact to S2I build dir (shared volume) for building container
                            sh "cp ${pwd()}/target/${ARTIFACT_FILE} ${S2I_DEPLOYMENTS_DIR}/${ARTIFACT_FILE}"
                        }
                    }
                }
            }
          
            stage('SonarQube Scan') {
                steps {
                    container('maven') {
                        dir("mvn-build") {
                            script {
                                try {
                                    withSonarQubeEnv('SonarQube-OpenShiftPoC') {
                                        // Run the maven build and trigger SonarQube scan
                                        sh "mvn sonar:sonar " +
                                                "-Dsonar.projectKey=${pom.groupId}:${pom.artifactId} " +
                                                "-Dsonar.host.url=${SONAR_HOST_URL} " +
                                                "-Dsonar.login=${SONAR_AUTH_TOKEN} " +
                                                "\"-Dsonar.projectName=${pom.artifactId}\" " +
                                                "\"-Dsonar.projectDescription=${pom.description}\" " +
                                                '-Dsonar.java.binaries=target ' +
                                                '-Dsonar.language=java ' +
                                                '-Dsonar.sourceEncoding=UTF-8 ' +
                                                "-P ${PLATFORM}"
                                        // PLATFORM maps to a profile defined in settings.xml in config map
                                    }
                                } catch (Exception e) {
                                    echo "Failed to perform SonarQube Scan...Ignore error and continue"
                                    SECURITY_QUALITY_PASS_FLAG &= false
                                }
                            }
                        }
                    }
                }
            }

            stage('Nexus IQ Scan') {
                steps {
                    container('maven') {
                        dir ("mvn-build") {
                         script {
                            catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE', SECURITY_QUALITY_PASS_FLAG: (SECURITY_QUALITY_PASS_FLAG & false)) {
                                nexusIQScan(
                                    IQ_STAGE: (("${CI_BUILD_TYPE}").contains('RELEASE')) ? 'release' : 'build',
                                    GSI: pom.properties['gsi-code'],
                                    ARTIFACT_ID: pom.artifactId
                                )
                            }                         
                         }
                      }
                    }
                }
            }

            stage('Veracode Scan') {
                steps {
                    container('maven') {
                        script {
                            catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE', SECURITY_QUALITY_PASS_FLAG: (SECURITY_QUALITY_PASS_FLAG & false)) {
                                veracodeScan(
                                        buildType: (("${CI_BUILD_TYPE}").contains('RELEASE')) ? 'release' : 'snapshot',
                                        gsiCode: pom.properties['gsi-code']
                                )
                            }
                        }
                    }
                }
            }
          
            stage('Publish Artifacts to Nexus Repo') {
               when {
                  allOf {
                    expression {SECURITY_QUALITY_PASS_FLAG == true}
                  }
                }
              steps {
                    container('maven') {
                        dir("mvn-build") {
                            sh "mvn ${CHANGE_LIST} -P tomcat deploy -e"
                            //nexusPublisher nexusInstanceId: 'nexus3', nexusRepositoryId: 'maven-releases', packages: [[$class: 'MavenPackage', mavenAssetList: [], mavenCoordinate: [artifactId: 'artifactID', groupId: 'groupId', packaging: 'jar', version: 'version']]], tagName: 'tag_to_use'
                        }
                    }
                }
            }
            stage('Build Image and Push to Registry') {
               when {
                  allOf {
                    expression {SECURITY_QUALITY_PASS_FLAG == true}
                  }
                }
                steps {
                    container('jnlp') {
                        script {
                            openshift.logLevel(3)
                            openshift.withCluster("${OPENSHIFT_CLUSTER_URL}") {
                                openshift.withCredentials("${OPENSHIFT_TOKEN}") {
                                    openshift.withProject("${pipelineParams.openshiftProject}") {
                                        openshift.newApp("-f", "pipelines/buildconfig.yaml",    \
                                           "-p", "BUILD_NAME=${BUILD_NAME}",     \
                                           "-p", "FROM_IMAGE=${BASE_IMAGE}",    \
                                           "-p", "TO_IMAGE=${NEXUS_REGISTRY_URL}/${APP_NAME}:${env.POM_VERSION}")

                                        openshift.selector("bc", "${BUILD_NAME}").startBuild("--from-dir=${MAVEN_ARTIFACTS}", "--wait=true")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        post {
            always {
                container('jnlp') {
                    echo "Cleaning up/Deleting app from OpenShift..."
                    script {
                        //sh "sleep 120"
                        openshift.logLevel(0)
                        openshift.withCluster("${OPENSHIFT_CLUSTER_URL}") {
                            openshift.withCredentials("${OPENSHIFT_TOKEN}") {
                                openshift.withProject("${pipelineParams.openshiftProject}") {
                                    openshift.selector('buildconfig', [app: "${BUILD_NAME}"]).delete()
                                }
                            }
                        }
                    }
                }
                echo "See explanation of common errors at https://test-1-confluence.mufgamericas.com/display/CICDDocs/Jenkins+Pipelines+Operations+Runbook#CommonErrors"
            }
        }
    }
}
======== docker_container_pipeline.groovy ========
/**
  * Most of the following is based on info from the Jenkins blog and documentation: 
  * https://jenkins.io/blog/2017/10/02/pipeline-templates-with-shared-libraries/
  * https://jenkins.io/doc/book/pipeline/shared-libraries/
  */
def call(body) {
    def pipelineParams = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    def USER_DOC_URL = 'https://prod-1-confluence.mufgamericas.com/display/CICDDocs/CICD+Pipelines+User+Documentation#CICDPipelinesUserDocumentation-PrerequisitesPrerequisites'
    def BUILD_NAME = ''

    pipeline {
        agent {
    	    kubernetes {
                cloud "${pipelineParams.openshiftCluster}-${pipelineParams.openshiftProject}"
    	        label "jenkins-${JOB_BASE_NAME}-${BUILD_NUMBER}"    //This can't exceed 63 chars
                yaml libraryResource('agent-pod-conf/jenkins-agent-pod.yaml')
    	    }
        }

        options {
            //GLOBAL BUILD OPTIONS
            buildDiscarder(logRotator(numToKeepStr: '8'))
            disableConcurrentBuilds()
            timeout(time: 15, unit: 'MINUTES')
        }

        environment {
          OPENSHIFT_CLUSTER_URL = "insecure://kubernetes.default.svc:443"
          OPENSHIFT_TOKEN = credentials("${pipelineParams.openshiftCluster}-${pipelineParams.openshiftProject}-jenkins")
        }
    
        stages {
           stage('Checkout Application Repo') {
               steps {
    		       container('jnlp') {
                       sh "mkdir docker-build"
                       dir ("docker-build") {

                           script {
                               // Validate inputs and set build params
                               assert "${GSI}" != null
                               assert "${BBK_PROJECT}" != null
                               assert "${BBK_REPO}" != null
                               assert "${BBK_REPO_URL}" != null
                               assert "${DOCKER_CONTEXT_DIR}" != null
                               assert "${BBK_REPO_BRANCH}" != null
                               BUILD_NAME= ("${JOB_BASE_NAME}-${BUILD_NUMBER}".toLowerCase()).replaceAll("[._]", "-")

                               if ( "${env.SKIP_GSI_PREFIX}" == 'true' ) {
                                   IMAGE_NAMESPACE = ""
                               } else {
                                   IMAGE_NAMESPACE = ("${GSI}/".toLowerCase()).replaceAll("[._]", "-")
                               }

                           // BBK_REPO_URL is propagated from the groovy script that create the build job
                           git branch: "${BBK_REPO_BRANCH}", 
                               url: "${BBK_REPO_URL}",
                               credentialsId: "${pipelineParams.bitbucket_credential_id}"
                           }
                       }
                   }
               }
           }
           stage('Build Image and Push to Registry') {
               steps {
                   container('jnlp') {
                       script {
                           openshift.logLevel(1)
                           openshift.withCluster( "${OPENSHIFT_CLUSTER_URL}" ) {
                               openshift.withCredentials( "${OPENSHIFT_TOKEN}" ) {
                                   openshift.withProject( "${pipelineParams.openshiftProject}" ) {

                                       def models = openshift.process("-f", "docker/buildconfig_docker.yaml", \
                                       "-p", "BUILD_NAME=${BUILD_NAME}", \
                                       "-p", "GIT_SRC_REPO=${BBK_REPO_URL}", \
                                       "-p", "GIT_BRANCH=${BBK_REPO_BRANCH}", \
                                       "-p", "CONTEXT_DIR=${DOCKER_CONTEXT_DIR}", \
                                       "-p", "IMAGE_NAMESPACE=${IMAGE_NAMESPACE}", \
                                       "--param-file=${WORKSPACE}/docker/common-docker-build-params", \
                                       "--param-file=${WORKSPACE}/docker-build/${DOCKER_CONTEXT_DIR}/docker-build-params"
                                       )
                                       openshift.logLevel(0)
                                       def created = openshift.create( models )
                                       def bc = created.narrow('bc')
                                       def build = bc.startBuild()
                                       build.logs('-f') // This will run to the end of the build
                                       if ( build.object().status.phase == "Failed" ) {
                                           error("${build.object().status.reason}")
                                       }
                                       echo "Built and Pushed Image - ${build.object().status.outputDockerImageReference}"
                                       
                                   }
                               }
                           }
                       }
                   }
               }
           }
        }
        post {
            success {
                container('jnlp') {
                    script {
                        echo "Successful build - Mark to Keep Forever"
                        currentBuild.keepLog = true
                    }
                }
            }
            always {
                container('jnlp') {
                    echo "Cleaning up/Deleting app from OpenShift..."
                    script {
                        openshift.logLevel(0)
                        openshift.withCluster( "${OPENSHIFT_CLUSTER_URL}" ) {
                            openshift.withCredentials( "${OPENSHIFT_TOKEN}" ) {
                                openshift.withProject( "${pipelineParams.openshiftProject}" ) {
                                    openshift.selector( 'buildconfig', [ app:"${BUILD_NAME}" ] ).delete()
                                }
                            }
                        }
                    }
                }
                echo "See explanation of common errors at ${USER_DOC_URL}"
            }
        }
    }
}

======== getAWSCredentialsFromSTS.groovy ========
#!/usr/bin/env groovy

def call(
        String username = "",
        String password = "",
        String account = "",
        String role = ""
) {
    assert username?.trim() : "username is required"
    assert password?.trim() : "password is required"
    assert account?.trim() : "account is required"
    assert role?.trim() : "role is required"

    def AWS_AUTH = sh (returnStdout: true, script: "set +x; exec /opt/app-root/src/aws_saml.sh -a ${account} -r ${role} -u ${username} -p \"${password}\"")

    return AWS_AUTH.split()
}======== ios_build_pipeline_multibranch.groovy ========
def call(
        String nexusBaseUrl = "",
        String nightlyJobBranches = Constants.NIGHTLY_JOB_BRANCH_REGEX_DEFAULT,
        String nightlyJobCronSchedule = Constants.NIGHTLY_JOB_CRON_SCHEDULE_DEFAULT
) {

    def DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX = /release.*|hotfix.*|master/
    def SECURITY_QUALITY_PASS_FLAG = true
    def NO_DEPLOYMENT_BRANCH_REGEX = /PR-.*|develop.*/
    assert nexusBaseUrl?.trim(): "Deployment nexus url is required."
    def NIGHTLY_JOB_BRANCH_REGEX = env.NIGHTLY_JOB_BRANCH_REGEX ?: nightlyJobBranches

    def VERACODE_SCAN_BRANCH_REGEX = /release.*|hotfix.*|develop|master/
    def ARCHIVE_DIR = 'UnionBank/target/xcarchive.xcarchive'
    def SCHEMA = params.Build ?: 'development'
    def APP_VERSION = ''

    pipeline {
        agent { label 'ios-mac-agent' }

        options {
            //GLOBAL BUILD OPTIONS
            buildDiscarder(logRotator(numToKeepStr: '8'))
            timeout(time: 45, unit: 'MINUTES')
        }

        environment {
            // These are for oc to make outbound connection to the OpenShift cluster URL
            GIT_AUTH = credentials('bitbucket-tagging')
        }

        parameters {
            choice(name: "Build", choices: ['development', 'qa', 'release'], description: 'build schema')
        }

        triggers {
            cron(("${env.BRANCH_NAME}" =~ ~/${NIGHTLY_JOB_BRANCH_REGEX}/) ? (env.NIGHTLY_JOB_CRON_SCHEDULE || nightlyJobCronSchedule) : "")
        }

        stages {
            stage("parameterizing") {
                steps {
                    script {
                        if ("${params.Invoke_Parameters}" == "Yes") {
                            currentBuild.result = 'ABORTED'
                            error('DRY RUN COMPLETED. JOB PARAMETERIZED.')
                        }
                        log.status("parameterizing", currentBuild.result)
                        log.info("Cron Schedule:" + ("${env.BRANCH_NAME}" =~ ~/${NIGHTLY_JOB_BRANCH_REGEX}/) ? (env.NIGHTLY_JOB_CRON_SCHEDULE || nightlyJobCronSchedule) : "")
                    }
                }
            }

            stage('Unit Testing') {
                steps {
                    script {
//                            sh "xcodebuild clean test -scheme ${SCHEMA} -workspace UnionBank/UnionBank.xcworkspace -destination 'name=iPhone 11'"
//                        sh "source ~/.bash_profile && cd UnionBank/fastlane && fastlane add_plugin appcenter && fastlane add_plugin versioning"
                        log.info(env.BRANCH_NAME)
                        log.info("testing complete .. waiting for code fixes..")
                    }

                }
            }

            stage('Binary build') {
                steps {
                    script {
                        withCredentials([usernamePassword(credentialsId: 'bitbucket-tagging', usernameVariable: 'gitUsername', passwordVariable: 'gitPassword')]) {
                            sh "rm -rf UnionBank/target"
                            sh "set +x && source ~/.bash_profile && set -x &&cd UnionBank/fastlane && fastlane ${SCHEMA} MATCH_GIT_BASIC_AUTHORIZATION:\"${gitUsername}:${gitPassword}\" --env ${SCHEMA} --verbose"
                        }
                        log.status("Binary build", currentBuild.result)
                    }

                }
            }

            stage('Integration Testing') {
                steps {
                    script {
                        log.info("Integration testing marker, Build is done...")
                    }

                }
            }

            stage("Archive Artifacts") {
                steps {
                    script {
                        archiveArtifacts allowEmptyArchive: true, fingerprint: true,
                                artifacts: '**/*.ipa, target/**/*.log, target/**/*.plist'
                        log.status("Archive Artifacts", currentBuild.result)
                    }
                }
            }

            stage('Nexus IQ Scan') {
                when {
                    not { buildingTag() }
                }
                steps {
                    script {
                        try {
                            nexusIQScan(
                                    IQ_STAGE: (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) ? 'release' : 'build',
                                    GSI: 'tab',
                                    ARTIFACT_ID: 'ios'
                            )
                        } catch (error) {
                            log.status("Nexus IQ Scan", currentBuild.result)
                            log.error("Failed to perform nexusIQ Scan...Ignore error and continue " + error.getMessage())
                            SECURITY_QUALITY_PASS_FLAG &= false
                        }
                    }
                }
            }

            stage('Veracode Scan') {
                when {
                    allOf {
                        expression { env.BRANCH_NAME =~ VERACODE_SCAN_BRANCH_REGEX }
                        not { buildingTag() }
                    }
                }
                steps {
                    script {
                        catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE', SECURITY_QUALITY_PASS_FLAG: (SECURITY_QUALITY_PASS_FLAG & false)) {
                            //resolve unknown usr error for scp
                            //https://blog.openshift.com/jupyter-on-openshift-part-6-running-as-an-assigned-user-id/
                            sh "/usr/local/bin/set-user-id && pwd"
                            sh "mv ${ARCHIVE_DIR}/Products/Applications ${ARCHIVE_DIR}/Payload"
                            sh "rm -r ${ARCHIVE_DIR}/Products"
                            sh "cd ${ARCHIVE_DIR} && zip -r ../tab-ios.bca * && cd ../../"
                            veracodeScan(
                                    buildType: ("${SCHEMA}".contains("release")) ? 'release' : 'snapshot',
                                    // gsiCode: env.GSI
                                    gsiCode: 'TAB (iOS)'
                            )
                            log.status("Veracode Scan", currentBuild.result)
                        }
                    }
                }
            }

            stage("Publish to Nexus") {
                when {
                    allOf {
//                        expression { SECURITY_QUALITY_PASS_FLAG == true }
                        expression { !(env.BRANCH_NAME =~ NO_DEPLOYMENT_BRANCH_REGEX) }
                        not { buildingTag() }
                    }
                }
                steps {
                    script {
                        def nexusUrl = ''
                        // get version from fastlane build TARGETED_RELEASE_VERSION_MAVEN
                        TARGETED_RELEASE_VERSION_MAVEN = sh(script: "set +x && source ~/.bash_profile && set -x && cd UnionBank/fastlane && fastlane version --env ${SCHEMA} | grep TARGETED_RELEASE_VERSION_MAVEN | cut -d' ' -f3", returnStdout: true).trim()
                        APP_VERSION = "${TARGETED_RELEASE_VERSION_MAVEN}" ?: "1.0.0-0.0.0"
                        if (!"${SCHEMA}".contains("release")) {
                            nexusUrl = "${nexusBaseUrl}/maven-snapshots/"
                            APP_VERSION = "${APP_VERSION}" + "-SNAPSHOT"
                        } else {
                            nexusUrl = "${nexusBaseUrl}/maven-releases/"
                        }
                        log.info("${nexusUrl}")
                        log.info("${APP_VERSION}")

                        sh('''
                               cd ${WORKSPACE}
                               set +x 
                               source ~/.bash_profile
                               set -x
                               for file in $(find ${WORKSPACE} -name "*.ipa" -type f); do
                                    file_wo_path=${file##*/}
                                    pom_file_version=''' + APP_VERSION + '''
                                    echo "file to be published: ${file_wo_path%\\.*} "
                                    mvn deploy:deploy-file -f scripts/pom.xml -DgroupId=com.unionbank.tab.ios -DartifactId=${file_wo_path%\\.*} -Dversion="${pom_file_version}" -Dpackaging=ipa -DrepositoryId=snapshots -Durl=''' + nexusUrl + ''' -Dfile=${file}
                               done
                              ''')
                        log.status("Publish to Nexus", currentBuild.result)
                    }
                }

            }

            stage("Upload to Appcenter") {
                when {
                    allOf {
//                        expression { SECURITY_QUALITY_PASS_FLAG == true }
                        not { buildingTag() }
                        expression { !(env.BRANCH_NAME =~ NO_DEPLOYMENT_BRANCH_REGEX) }
                    }
                }
                steps {
                    script {
                        sh "set +x && source ~/.bash_profile && set -x && cd ${WORKSPACE}/UnionBank/fastlane &&  fastlane appcenter --env ${SCHEMA} --verbose"
                        log.status("Upload to Appcenter", currentBuild.result)
                    }
                }
            }
            stage("Tagging Git") {
                when {
                    allOf {
//                        expression { SECURITY_QUALITY_PASS_FLAG == true }
                        not { buildingTag() }
                        expression { !(env.BRANCH_NAME =~ NO_DEPLOYMENT_BRANCH_REGEX) }
                    }
                }
                steps {
                    script {
                        sh('''
                            set +x 
                            source ~/.bash_profile
                            set -x
                            git config --local credential.helper "!f() { echo username=\$GIT_AUTH_USR; echo password=\$GIT_AUTH_PSW; }; f"
                            cd ${WORKSPACE}/UnionBank/fastlane 
                            fastlane tag --env \${SCHEMA} --verbose
                        ''')
                        log.status("Tagging Git", currentBuild.result)
                    }
                }
            }
        }
        post {
            always {
                deleteDir()
            }
        }
    }
}======== java_build_pipeline_multibranch.groovy ========
import com.mufg.tsi.devsecops.pipelines.Helper

class BuildConfiguration {
    def id = ""
    def baseImage = ""
    def buildOption = ""
    def packagingType = ""
}

def call(
        String openshiftCluster = "",
        String openshiftProject = "",
        String dockerRegistryURL = "",
        String baseImageJava = "openjdk/openjdk-11-rhel7:1.1-4",
        String baseImageTomcat = "jboss-webserver-5/webserver52-openjdk11-tomcat9-openshift-rhel7:1.0-5.1575996526",
        String baseImageJboss = "jboss-eap-7/eap72-openjdk11-openshift-rhel8:1.2-5"
) {

    assert openshiftCluster?.trim(): "openshift Cluster is required."
    assert openshiftProject?.trim(): "openshift Project is required."
    assert dockerRegistryURL?.trim(): "dockerRegistryURL is required."
    assert env.OPENSHIFT_APP_PROJECTS?.trim(): "Env OPENSHIFT_APP_PROJECTS is required"

    def DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX = /release.*|hotfix.*|develop|master/
    def SECURITY_QUALITY_PASS_FLAG = true
    def LOG_LEVEL = 2
    def POM_RELEASE = env.BRANCH_NAME
    def POM = ''
    def USER_DOC = 'https://prod-1-confluence.mufgamericas.com/display/CICDDocs/CICD+Pipelines+User+Documentation'
    def pomQualityCheckOverrideProperties = [
            'cpd.skip',
            'checkstyle.skip',
            'pmd.skip',
            'skip.pmd.check',
            'maven.test.skip',
    ]

    BuildConfiguration[] buildConfigurations = [
            new BuildConfiguration(id: "java", baseImage: env.BASE_IMAGE_JAVA ?: baseImageJava, buildOption: env.BUILD_OPTION_JAVA ?: "", packagingType: "jar|pom"),
            new BuildConfiguration(id: "tomcat", baseImage: env.BASE_IMAGE_TOMCAT ?: baseImageTomcat, buildOption: env.BUILD_OPTION_TOMCAT ?: "-Ddocker.containerizing.mode=exploded", packagingType: "war|pom"),
            new BuildConfiguration(id: "jboss", baseImage: env.BASE_IMAGE_JBOSS ?: baseImageJboss, buildOption: env.BUILD_OPTION_JBOSS ?: "-Djib.container.appRoot=/opt/eap/standalone/deployments", packagingType: ".*")
    ] as BuildConfiguration[]
    //This variable used for  setting parameters "No Deployment" choice and verify user choose "No Deployment"
    NO_DEPLOYMENT = 'No Deployment'
    CLUSTER_CONFIG_FILE = 'com/mufg/tsi/devsecops/pipelines/openshift.yaml'
    //This flag is used to carry deployment status true: deploy, false: no deploy
    DEPLOYMENT_FLAG = true
    VERACODE_SCRIPTS = 'com/mufg/tsi/devsecops/pipelines/bin/vcode_helper.sh'
    EMERGENCY_CM_APPROVERS_FILE = 'change_management/emergency_approvers.yaml'
    CUCUMBER_REPORT = 1

    final Map<String, Map<String, String>> CLUSTER_MAP = new HashMap<>()

    pipeline {
        agent {
            kubernetes {
                cloud "${openshiftCluster}-${openshiftProject}"
                label "jenkins-agent-pod-multibranch-${openshiftProject}-${BUILD_NUMBER}"
                yaml libraryResource("agent-pod-conf/jenkins-agent-pod-multibranch.yaml")
            }
        }
        options {
            //GLOBAL BUILD OPTIONS
            buildDiscarder(logRotator(numToKeepStr: '13'))
            disableConcurrentBuilds()
            timeout(time: 180, unit: 'MINUTES')
        }

        environment {
            NEXUS_REGISTRY_URL = "${dockerRegistryURL}"
            // This path is from a emptydir volume primarily for  multiple containers to share files
            MAVEN_ARTIFACTS = '/var/tmp/maven-artifacts'

            // These are for oc to make outbound connection to the OpenShift cluster URL
            PROXY = 'http://ub-app-proxy.uboc.com:80'
            HTTP_PROXY = "${env.PROXY}"
            HTTPS_PROXY = "${env.PROXY}"
            // This is a hack to force connectivity to these cluster to go direct
            NO_PROXY = "opn-console-mmz-uat1.unionbank.com,opn-console-mmz-uat2.opn.unionbank.com,.opn.unionbank.com"
        }

        parameters {
            choice(name: "Deployment", choices: "${fetchClustersFromOnboardingFile(CLUSTER_MAP)}", description: "Choose the environment to deploy.")
            string(name: 'ChangeNumber', defaultValue: '', description: 'Change Management Ticket Number - e.g. CHG1234567. This must be provided for production deployment or the deployment will be denied. For emergency deployment, i.e. in case an emergency change management tickat can\'t be obtained, enter \"emergency\" and reach out to the pipeline operations team for approval.')
            string(name: 'CucumberTags', defaultValue: '', description: 'Cucumber tags for testing such as @SIT,@CIF separated with comma')
            string(name: 'testsToRun', defaultValue: '', description: 'ALM Octane Test Parameters')
        }

        stages {
            stage("parameterizing") {
                steps {
                    script {
                        if ("${params.Invoke_Parameters}" == "Yes") {
                            currentBuild.result = 'ABORTED'
                            error('DRY RUN COMPLETED. JOB PARAMETERIZED.')
                        }
                        // determine deployment is required when parameterizing
                        //set DEPLOYMENT_FLAG = false when deployment should be stoped
                        if ((!(env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) && !env.TAG_NAME)) {
                            DEPLOYMENT_FLAG = false
                            echo "No Deployment:  Branch chosen ${env.BRANCH_NAME} is not in defined the allowed list ${DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX} or TAG_NAME is not defined ${env.TAG_NAME}"
                        }

                        if ("${NO_DEPLOYMENT}" == "${params.Deployment}" || "${params.Deployment}" == "") {
                            DEPLOYMENT_FLAG = false
                            echo "No Deployment build was chosen"
                        }

                        if (!env.K8S_DEPLOYMENT_CONFIG || env.K8S_DEPLOYMENT_CONFIG?.trim() == "" || !env.K8S_DEPLOYMENT_REPOSITORY || env.K8S_DEPLOYMENT_REPOSITORY?.trim() == "") {
                            DEPLOYMENT_FLAG = false
                            echo "No Deployment configuration"
                        }
                    }
                }
            }
            stage('Validate POM File') {
                steps {
                    container('maven') {
                        script {
                            POM = readMavenPom file: 'pom.xml'
                            assert POM.properties['gsi-code']: "Invalid pom.xml - See details at ${USER_DOC}"
                            def pomVersion = POM.version
                            if (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) {
                                if (pomVersion.startsWith('${revision}')) {
                                    pomVersion = POM.properties['revision']
                                }
                                echo "Release pom version: ${pomVersion}"
                            } else {
                                echo "Non release pom version: ${POM.version} ${env.BRANCH_NAME}"
                                if (env.TAG_NAME) {
                                    pomVersion = env.BRANCH_NAME
                                }
                            }
                            env.POM_VERSION = pomVersion.replaceAll("-SNAPSHOT", "") + "${escapeBranch()}"

                            echo "pom version original: ${POM.version} "
                            echo "branch ${escapeBranch()}"
                            echo "Build Number: ${env.BUILD_NUMBER}"

                            try {
                                for (property in pomQualityCheckOverrideProperties) {
                                    overrideValue = POM.properties[property];
                                    if (overrideValue != null) {
                                        echo "${property}: ${overrideValue}";
                                        assert !overrideValue.toBoolean();
                                    }
                                }
                                assert POM.properties['maven.pmd.maxAllowedViolations'] == null
                            } catch (AssertionError e) {
                                echo "Invalid POM Detected - Evil No Bad – Don’t turn off quality checks, this is against Bank Procedures - See details at ${USER_DOC}"
                                currentBuild.result = 'FAILURE'
                                throw e
                            }
                        }
                        echo "POM_VERSION: ${env.POM_VERSION}"
                    }
                }
            }

            stage('Build Binaries') {
                when {
                    not { buildingTag() }
                }
                steps {
                    script {
                        container('maven') {
                            def cucumberTag = "${params.CucumberTags}" == "" ? '' : "-Dcucumber.options=\"--tags ${params.CucumberTags}\""
                            def testsToRun = params.testsToRun ?: ''
                            def buildCommand = "mvn clean install ${cucumberTag}"
                            if (testsToRun != "") {
                                echo "test to run: ${testsToRun}"
                                convertTestsToRun format: '', framework: 'cucumber_jvm'
                                buildCommand = buildCommand + "-Dfeatures=\"$testsToRunConverted\""
                            }
                            sh buildCommand
                            CUCUMBER_REPORT = sh(script: "find ${WORKSPACE} -name *cucumber*.json | grep .", returnStatus: true)
                        }
                    }
                }
            }

            stage('Nexus IQ Scan') {
                when {
                    not { buildingTag() }
                }
                steps {
                    container('maven') {
                        script {
                            catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE', SECURITY_QUALITY_PASS_FLAG: (SECURITY_QUALITY_PASS_FLAG & false)) {
                                nexusIQScan(
                                        IQ_STAGE: (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) ? (("${env.BRANCH_NAME}" == "develop") ? 'build' : 'release') : 'build',
                                        GSI: POM.properties['gsi-code'],
                                        ARTIFACT_ID: POM.artifactId
                                )
                            }
                        }
                    }
                }
            }

            stage('Veracode Scan') {
                when {
                    not { buildingTag() }
                }
                steps {
                    container('maven') {
                        script {
                            catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE', SECURITY_QUALITY_PASS_FLAG: (SECURITY_QUALITY_PASS_FLAG & false)) {
                                veracodeScan(
                                        buildType: (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) ? (("${env.BRANCH_NAME}" == "develop") ? 'snapshot' : 'release') : 'snapshot',
                                        gsiCode: POM.properties['gsi-code']
                                )
                            }
                        }
                    }
                }
            }

            stage('Publish Artifacts') {
                when {
                    allOf {
                        expression { SECURITY_QUALITY_PASS_FLAG }
                        not { buildingTag() }
                    }
                }
                environment {
                    GIT_AUTH = credentials('bitbucket-tagging')
                }
                steps {
                    script {
                        container('maven') {
                            if (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) {
                                try {
                                    sh "mvn versions:set -DnewVersion=${env.POM_VERSION}"

                                    POM_RELEASE = "v${env.POM_VERSION}"
                                    //Assuming the parent pom is always enterprise-tomcat/java/jboss-pom with buildType between - and -
                                    def buildTypes
                                    if (POM.parent != null && POM.parent.artifactId != null) {
                                        buildTypes = "${POM.parent.artifactId}".split('-')
                                    } else {
                                        buildTypes = []
                                    }
                                    def buildConfiguration = buildConfigurations.find { it.id == (buildTypes.size() > 1 ? buildTypes[1] : "java") } ?: buildConfigurations[0]
                                    assert (POM.packaging =~ /${buildConfiguration.packagingType}/): "Project packaging mismatches base image. Jar for spring boot, war for tomcat and EAP for any. ${buildConfiguration.packagingType}"
                                    def buildCommandLine = "${buildConfiguration.buildOption} -Ddocker.image.tag=${POM_RELEASE} -Dbase.image=${buildConfiguration.baseImage}"
                                    def buildProfile = env.BUILD_PROFILE ?: 'docker'

                                    echo "Build Command Line: ${buildCommandLine}"
                                    echo "Build Profile: ${buildProfile}"

                                    //skip testing in deployment phase

                                    sh "mvn deploy -P ${buildProfile} ${buildCommandLine} -DskipTests=true"

                                    //Tagging
                                    //String NEXUS = "${dockerRegistryURL}".split(':')[0]
                                    //env.NEXUS_URL = "https://${NEXUS}"
                                    //String deploymentName = params.Deployment
                                    //Map<String, String> clusterEnv = CLUSTER_MAP.get(deploymentName.trim())
                                    //String environment = clusterEnv.get("environment")
                                    //tagAssociateAPI(environment, env.POM_VERSION, POM.artifactId)

                                    sh "git tag ${POM_RELEASE} -am \"Jenkins release tag\""
                                    sh('''
                                    git config --local credential.helper "!f() { echo username=\\$GIT_AUTH_USR; echo password=\\$GIT_AUTH_PSW; }; f"
                                    git push origin --tags
                                ''')
                                } catch (error) {
                                    echo "Exception in deploy: " + error.getMessage()
                                    throw error
                                }
                            } else {
                                echo "Code is not in a deploy branch. Bypassing deployment build."
                            }
                        }
                    }
                }
            }

            stage('Change Management Validation') {
                when {
                    allOf {
                        expression { SECURITY_QUALITY_PASS_FLAG }
                    }
                }
                steps {
                    container('python3') {
                        script {
                            if ((!(env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) && !env.TAG_NAME)
                                    || "${NO_DEPLOYMENT}" == "${params.Deployment}"
                                    || "${params.Deployment}" == "") {
                                echo "No deployment."
                            } else {
                                echo "Deplolyment cluster: ${params.Deployment}"
                                String deploymentName = params.Deployment
                                Map<String, String> clusterEnv = CLUSTER_MAP.get(deploymentName.trim())
                                String environment = clusterEnv.get("environment")
                                String clusterURL = getEnvUrl(clusterEnv.get("cluster"))
                                String project = clusterEnv.get("project")
                                String cluster = clusterEnv.get("cluster")

                                if (Helper.isCMRequired(environment)) {
                                    // if ticket number = emergency, allow for emergency deployment by the pipeline operations team
                                    if (params.ChangeNumber.trim().toLowerCase() == "emergency") {
                                        echo "########## IMPORTANT ##########"
                                        echo "This is an emergency deployment to Production. Explicit approval by the pipeline operations team is required. Please have the operations team work alongside you to approve the deployment."
                                        echo "Emergency deployment should be a last resort to resolve a pressing Production outage in case an emergency change management ticket can't be obtained. A post-event change management ticket must be raised to cover this emergency change."
                                        def userInput = ''
                                        timeout(time: 15, unit: "MINUTES") {
                                            userInput = input(message: 'Pipeline Operator: Do you approve this emergency deployment to Production?',
                                                    ok: 'Approve',
                                                    submitter: getEmergencyCMApprovers(),
                                                    parameters: [
                                                            [$class      : 'TextParameterDefinition',
                                                             defaultValue: '',
                                                             description : 'Enter "Approved" literally to approve or the deployment will be aborted.',
                                                             name        : '']
                                                    ])
                                        }
                                        if (userInput.trim().toLowerCase() != "approved") {
                                            error "Invalid value for approving emergency deployment. Deployment aborted."
                                        }
                                        echo "########## IMPORTANT ##########"
                                        echo "Emergency deployment approved. Proceed to deployment."
                                    } else {
                                        echo "This is a Production Deployment. Validate Change Ticket with CM (ServiceNow)."
                                        try {
                                            validateChangeManagementTicket(
                                                    gsiCode: "${env.GSI}",
                                                    changeNumber: "${params.ChangeNumber}"
                                            )
                                            //TO-DO - validate cluster/project with labels matching environment
                                        } catch (e) {
                                            //currentBuild.result = "FAILURE"
                                            error "Change Management Validation Failed. See logs for more details.\n${e}"
                                        }
                                    }
                                } else {
                                    echo "Change Management is not required for ${environment} environment."
                                }
                            }
                        }
                    }
                }
            }

            stage('Deploy to Target Environment') {
                when {
                    allOf {
                        expression { SECURITY_QUALITY_PASS_FLAG }
                        expression { DEPLOYMENT_FLAG }
                    }
                }
                steps {
                    container('jnlp') {
                        script {
                            if ((!(env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) && !env.TAG_NAME)
                                    || "${NO_DEPLOYMENT}" == "${params.Deployment}"
                                    || "${params.Deployment}" == "") {
                                echo "No deployment."
                            } else {
                                echo "Deplolyment clusters: ${params.Deployment}"
                                if (env.K8S_DEPLOYMENT_REPOSITORY) {
                                    //assuming deployment repo has the same branch name as environment name except prd for master
                                    assert env.K8S_DEPLOYMENT_CONFIG: "K8S_DEPLOYMENT_CONFIG is required to deploy. Assuming the IMAGE_VERSION will be replaced with current build version"
                                    String deploymentName = params.Deployment
                                    Map<String, String> clusterEnv = CLUSTER_MAP.get(deploymentName.trim())
                                    String branchName = clusterEnv.get("environment")
                                    branchName = "prd".equalsIgnoreCase(branchName) ? "master" : branchName
                                    dir('deployment-repo') {
                                        git branch: branchName, url: env.K8S_DEPLOYMENT_REPOSITORY, credentialsId: 'bitbucket'
                                        try {
                                            String clusterURL = getEnvUrl(clusterEnv.get("cluster"))
                                            String project = clusterEnv.get("project")
                                            String cluster = clusterEnv.get("cluster")
                                            String tokenId = cluster + '-' + project + '-jenkins'
                                            String imageTagName = env.IMAGE_VERSION_TAG ?: 'IMAGE_VERSION'

                                            echo "Deployment Name: ${deploymentName}"
                                            echo "clusterEnv: ${clusterEnv}"
                                            echo "Cluster Map: ${CLUSTER_MAP}"
                                            echo "Cluster URL: ${clusterURL}"
                                            echo "TOKEN ID: ${tokenId}"
                                            echo "image version tag: ${imageTagName}"

                                            sh "find . -type f -exec sed -i s/${imageTagName}/${POM_RELEASE}/g {} \\;"
                                            withCredentials([string(credentialsId: "${tokenId}", variable: 'TOKEN')]) {
                                                openshift.logLevel(LOG_LEVEL)
                                                applyFile(
                                                        projectName: project,
                                                        deploymentFile: "${env.K8S_DEPLOYMENT_CONFIG}",
                                                        clusterAPI: clusterURL,
                                                        clusterToken: "${TOKEN}"
                                                )
                                            }

                                            //Tagging
                                            //String NEXUS = "${dockerRegistryURL}".split(':')[0]
                                            //env.NEXUS_URL = "https://${NEXUS}"
                                            //String environment = clusterEnv.get("environment")
                                            //tagAssociateAPI(environment, env.POM_VERSION, POM.artifactId)

                                            if (Helper.isCMRequired(clusterEnv.get("environment"))) {
                                                // Preserve build for successful Production deployment
                                                currentBuild.keepLog = true
                                            }
                                        } catch (e) {
                                            echo "Error encountered: " + e.getMessage()
                                            throw e
                                        }
                                    }
                                } else {
                                    echo "No deployment repo is specified! No deployment."
                                }
                            }
                        }
                    }
                }
            }
        }
        post {
            always {
                script {
                    echo "cucumber report ${CUCUMBER_REPORT}"
                    if (CUCUMBER_REPORT == 0) {
                        cucumber '**/*cucumber*.json'
                        publishGherkinResults ''
                    }
                }
            }
        }
    }
}

def escapeBranch() {
    if (env.BRANCH_NAME =~ /release.*|develop|master/) {
        return "." + "${env.BUILD_NUMBER}"
    }
    return "-" + "${env.BRANCH_NAME}".replace('/', '-') + "." + "${env.BUILD_NUMBER}"
}

def fetchClustersFromOnboardingFile(Map<String, Map<String, String>> clustersMap) {
    List<Map<String, String>> clustersData = Helper.base64DecodeMap(env.OPENSHIFT_APP_PROJECTS)
    echo "OPENSHIFT_APP_PROJECTS: ${clustersData}"
    StringBuffer clusterNames = new StringBuffer()
    for (Map<String, String> map : clustersData) {
        StringBuffer individualName = new StringBuffer()
        for (Map.Entry<String, String> entry : map.entrySet()) {
            individualName.append(entry.getKey()).append(":").append(entry.getValue()).append(" ")
        }
        clustersMap.put(individualName.toString().trim(), map)
        clusterNames.append(individualName.toString()).append("\n")
    }
    clusterNames.append("${NO_DEPLOYMENT}")
    return clusterNames.toString()
}

def getEnvUrl(String envName) {
    def data = libraryResource "${CLUSTER_CONFIG_FILE}"
    return Helper.lookupClusterURL(data, envName)
}

def getEmergencyCMApprovers() {
    def data = libraryResource "${EMERGENCY_CM_APPROVERS_FILE}"
    return Helper.getEmergencyCmApprovers(data)
}
======== js_build_aws_pipeline_multibranch.groovy ========
/**

 * Most of the following is based on info from the Jenkins blog and documentation:
 * https://jenkins.io/blog/2017/10/02/pipeline-templates-with-shared-libraries/
 * https://jenkins.io/doc/book/pipeline/shared-libraries/
 */
import com.mufg.tsi.devsecops.pipelines.Helper

class BuildConfiguration {
    def id = ""
    def baseImage = ""
    def buildOption = ""
    def packagingType = ""
}

def call(
        String openshiftCluster = "",
        String openshiftProject = "",
        String dockerRegistryURL = "",
        String baseImageFrontend = "rhscl/httpd-24-rhel7:2.4-109",
        String nexusUrl = "",
        String nexusArtifactRepo = "npm-hosted",
        String nexusCredentialId = "nexus-prod-user-token",
        String npmRepo = "",
        String sonarQubeEnv = ""
) {
    def BBK_PROJECT
    def BBK_REPO
    def BUILD_NAME

    def DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX = /release.*|hotfix.*|develop|master/
    def SECURITY_QUALITY_PASS_FLAG = true
    def DEFAULT_SONAR_ENV = 'SonarQube-OpenShiftPoC'
    def LOG_LEVEL = 2
    def RELEASE_VERSION = env.BRANCH_NAME
    def USER_DOC = 'https://prod-1-confluence.mufgamericas.com/display/CICDDocs/CICD+Pipelines+User+Documentation#CICDPipelinesUserDocumentation-PrerequisitesPrerequisites'
    BuildConfiguration[] buildConfigurations = [
            new BuildConfiguration(id: "javascript", baseImage: env.BASE_IMAGE_FRONTEND ?: baseImageFrontend, buildOption: env.BUILD_OPTION_FRONTEND ?: "", packagingType: "container"),
    ] as BuildConfiguration[]

    NO_DEPLOYMENT = 'No Deployment'
    CLUSTER_CONFIG_FILE = 'com/mufg/tsi/devsecops/pipelines/openshift.yaml'
    VERACODE_SCRIPTS = 'com/mufg/tsi/devsecops/pipelines/bin/vcode_helper.sh'

    BUILD_GIT_SRC_REPO = "https://bbk.unionbank.com/scm/dsops/groovy_sharedlibs.git"
    BUILD_GIT_BRANCH = env.BUILD_GIT_BRANCH ? env.BUILD_GIT_BRANCH : "master"
    BUILD_CONTEXT_DIR = "resources/docker-build/javascript"

    final Map<String, Map<String, String>> CLUSTER_MAP = new HashMap<>()

    pipeline {
        agent {
            kubernetes {
                cloud "${openshiftCluster}-${openshiftProject}"
                label "jenkins-agent-pod-js-${openshiftProject}-${BUILD_NUMBER}"
                //This can't exceed 63 chars
                yaml libraryResource("agent-pod-conf/jenkins-agent-pod-js-aws.yaml")
            }
        }
        options {
            //GLOBAL BUILD OPTIONS
            buildDiscarder(logRotator(numToKeepStr: '13'))
            disableConcurrentBuilds()
            timeout(time: 45, unit: 'MINUTES')
        }

        environment {
            // This path is from a emptydir volume primarily for  multiple containers to share files
            ARTIFACTS_DIR = '/var/tmp/artifacts'
            // These are for oc to make outbound connection to the OpenShift cluster URL
            PROXY = 'http://ub-app-proxy.uboc.com:80'
            HTTP_PROXY = "${env.PROXY}"
            HTTPS_PROXY = "${env.PROXY}"
            NO_PROXY = "kubernetes.default.svc,opn-console-mmz-uat1.unionbank.com,opn-console-mmz-uat2.opn.unionbank.com"
            NEXUS_URL="${nexusUrl}"
            OPENSHIFT_CLUSTER_URL = "insecure://kubernetes.default.svc:443"
            OPENSHIFT_TOKEN = credentials("${openshiftCluster}-${openshiftProject}-jenkins")
        }
        stages {
            stage("parameterizing") {
                steps {
                    script {
                        if ("${params.Invoke_Parameters}" == "Yes") {
                            currentBuild.result = 'ABORTED'
                            error('DRY RUN COMPLETED. JOB PARAMETERIZED.')
                        }

                        // GIT_URL is not present on pull requests
                        GIT_URL = getUrl(env.GIT_URL, env.GIT_URL_1)

                        // Parse the BitBucket Project and Repo from the URL
                        BBK_PROJECT = GIT_URL.split('/')[4]
                        BBK_REPO = GIT_URL.split('/')[5].split('\\.')[0]
                        APP_NAME = ("${BBK_PROJECT}-${BBK_REPO}".toLowerCase()).replace(".", "-")
                        BUILD_NAME = ("${APP_NAME}-${BUILD_NUMBER}".toLowerCase()).replace(".", "-")
                    }
                }
            }

            stage('Validate package.json') {
                steps {
                    container('node') {
                        script {
                            env.PAM_URL="https://pam.unionbank.com/"
                            env.ARTIFACTID = sh(script: "cat package.json | jq -r .name", returnStdout: true).trim()
                            try {
                               sh(script: """
                                    grep -q BUILD package.json
                                    if [ -f src/package.json ]; then
                                        sed -i 's/BUILD/'"${BUILD_NUMBER}"'/' src/package.json 
                                    else 
                                        sed -i 's/BUILD/'"${BUILD_NUMBER}"'/' package.json   
                                    fi 
                               """)
                            } catch (error) {
                                echo "Invalid package.json - Versioning failed, make sure that version is x.x.x-BUILD (e.g. 1.0.0-BUILD)"
                                currentBuild.result = 'FAILURE'
                                throw error
                            }

                            // Validate inputs and set build params
                            assert "${GSI}" != null
                            assert "${BBK_PROJECT}" != null
                            assert "${BBK_REPO}" != null

                            try {
                                env.ARTIFACTID = sh(script: "cat package.json | jq -r .name", returnStdout: true).trim()
                                env.VERSION = sh(script: "cat package.json | jq -r .version", returnStdout: true).trim()
                               // env.GSI = sh(script: "cat package.json | jq -r .gsi", returnStdout: true).trim()

                                assert env.ARTIFACTID != null
                                assert env.VERSION != null

                            } catch (AssertionError e) {
                                echo "Invalid package.json - See details at ${USER_DOC}"
                                currentBuild.result = 'FAILURE'
                                throw e
                            }
                            try {
                                assert env.GSI != null
                            } catch (AssertionError e) {
                                echo "Missing critical config parameters at job config"
                                currentBuild.result = 'FAILURE'
                                throw e
                            }
                            env.ARTIFACTFILE = "${env.VERSION}"

                            if (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) {
                                env.VERSION = "${env.VERSION}" + "${escapeBranch()}"
                                echo "Release version: ${env.VERSION}"
                            } else {
                                echo "Non release version: ${env.VERSION} ${env.BRANCH_NAME}"
                                if (env.TAG_NAME) {
                                    env.VERSION = env.BRANCH_NAME
                                }
                            }
                            echo "version original: ${env.VERSION} "
                            echo "branch ${escapeBranch()}"
                            echo "Build Number: ${env.BUILD_NUMBER}"
                            echo "VERSION: ${env.VERSION}"
                        }
                    }
                }
            }

            stage('Load Jenkinsfile') {
                when {
                    expression { env.JENKINSFILE_NAME != null }
                }
                steps {
                    script{
                          env.NEXUS_CREDENTIAL_ID="${nexusCredentialId}"
                    }
                  
                    echo "Loading ${env.JENKINSFILE_PATH}/${env.JENKINSFILE_NAME}"
                    load "${env.JENKINSFILE_PATH}/${env.JENKINSFILE_NAME}"
                }
            }
            stage('Nexus IQ Scan') {
                when {
                    not { buildingTag() }
                }
                steps {
                    container('node') {
                        script {
                            catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE', SECURITY_QUALITY_PASS_FLAG: (SECURITY_QUALITY_PASS_FLAG & false)) {
                                nexusIQScan(
                                        IQ_STAGE: (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) ? 'release' : 'build',
                                        GSI: env.GSI,
                                        ARTIFACT_ID: env.ARTIFACTID
                                )
                            }
                        }
                    }
                }
            }
            stage('Veracode Scan') {
               when {
                   not { buildingTag() }
               }
               steps {
                   container('jnlp') {
                       script {
                             sh(script: """
                                   mkdir target
                                   mv /var/tmp/artifacts/* ./target/   
                                """)
                           veracodeScan(
                                   buildType: (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) ? 'release' : 'snapshot',
                                   gsiCode: env.GSI
                           )
                       }
                   }
               }
            }

            stage('Publish Artifacts') {
                when {
                    allOf {
                        expression { SECURITY_QUALITY_PASS_FLAG == true }
                        not { buildingTag() }
                    }
                }
                environment {
                    GIT_AUTH = credentials('bitbucket-tagging')
                }
                steps {
                    script {
                        container('jnlp') {
                            if (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) {
                                try {
                                    RELEASE_VERSION = "v${env.VERSION}"

                                    sh "git config --global user.name 'Jenkins Agent'"
                                    sh "git config --global user.email 'unused@us.mufg.jp'"
                                    sh "git tag ${RELEASE_VERSION} -am \"Jenkins release tag\""
                                    sh('''
                                    git config --local credential.helper "!f() { echo username=\\$GIT_AUTH_USR; echo password=\\$GIT_AUTH_PSW; }; f"
                                    git push origin --tags
                                    ''')
                                } catch (error) {
                                    echo "Exception in deploy: " + error.getMessage()
                                    throw error
                                }
                            } else {
                                echo "Code is not in a deploy branch. Bypassing deployment build."
                            }
                        }   
                        container('node') {
                            if (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) {
                                // Publish artifact to Nexus
                                withCredentials([
                                        usernamePassword(credentialsId: "${nexusCredentialId}",
                                                usernameVariable: 'NEXUS_USERNAME',
                                                passwordVariable: 'NEXUS_PASSWORD')]) {

                                    env.NEXUS_CONTENT_PATH = "${env.GSI}/${BBK_PROJECT}/${BBK_REPO}/${env.VERSION}"
                                    //publish but first check if there is a package.json in the dist folder
                                    String npm_publish = sh(script: """
                                            if [ -f dist/package.json ]; then
                                                cd dist 
                                                npm publish --registry  ${nexusUrl}/repository/npm-hosted/
                                             else 
                                                npm publish --registry  ${nexusUrl}/repository/npm-hosted/
                                            fi
  
                                        """, returnStdout: true).trim()

                                    println "npm publish result $npm_publish"
                                    env.DEPLOY_ARTIFACT=sh(script: "echo $npm_publish | cut -d' ' -f2", returnStdout: true).trim()
                                }
                            } else {
                                echo "Code is not in a deploy branch. Bypassing deployment build."
                            }

                        }
                    }
                }
            }

            stage('Load Deployment File') {
             when {
                    allOf {
                        expression { SECURITY_QUALITY_PASS_FLAG == true }
                    }
                }               
                steps {
                    script {
                 if ((!(env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) && !env.TAG_NAME)
                        || "${NO_DEPLOYMENT}" == "${params.Deployment}"
                        || "${params.Deployment}" == "") {
                    echo "No deployment."
                }else {                   
                    echo "Loading ./Jenkinsfile.deploy"
                    echo "No deployment."
                }
                }
                }
            }
     
        }
    }
}

def escapeBranch() {
    if (env.BRANCH_NAME =~ /release.*|develop|master/) {
        return "." + "${env.BUILD_NUMBER}"
    }
    return "-" + "${env.BRANCH_NAME}".replace('/', '-') + "." + "${env.BUILD_NUMBER}"
}

def getEnvUrl(String envName) {
    def data = libraryResource "${CLUSTER_CONFIG_FILE}"
    return Helper.lookupClusterURL(data, envName)
}

def getUrl(gitUrl, GIT_URL_1) {
    if (gitUrl) {
        return gitUrl;
    } else if (GIT_URL_1) {
        return GIT_URL_1;
    } else {
        return "https://bbk.unionbank.com/scm/${env.CHANGE_URL.split('/')[4].toLowerCase()}/${env.CHANGE_URL.split('/')[6].toLowerCase()}";
    }
}======== js_build_pipeline_multibranch.groovy ========
/**
 * Most of the following is based on info from the Jenkins blog and documentation:
 * https://jenkins.io/blog/2017/10/02/pipeline-templates-with-shared-libraries/
 * https://jenkins.io/doc/book/pipeline/shared-libraries/
 */
import com.mufg.tsi.devsecops.pipelines.Helper

class BuildConfiguration {
    def id = ""
    def baseImage = ""
    def buildOption = ""
    def packagingType = ""
}

def call(
        String openshiftCluster = "",
        String openshiftProject = "",
        String dockerRegistryURL = "",
        String baseImageFrontend = "rhscl/httpd-24-rhel7:2.4-109",
        String nexusUrl = "",
        String nexusArtifactRepo = "angular-artifacts",
        String nexusCredentialId = "nexus-user-token",                                                                 
        String npmRepo = "",
        String sonarQubeEnv = ""
) {

    assert openshiftCluster?.trim(): "openshift Cluster is required."
    assert openshiftProject?.trim(): "openshift Project is required."
    assert dockerRegistryURL?.trim(): "dockerRegistryURL is required."
    assert env.OPENSHIFT_APP_PROJECTS?.trim(): "Env OPENSHIFT_APP_PROJECTS is required"

    def DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX = /release.*|hotfix.*|develop|master/
    def SECURITY_QUALITY_PASS_FLAG = true
    def DEFAULT_SONAR_ENV = 'SonarQube-OpenShiftPoC'
    def LOG_LEVEL = 2
    def RELEASE_VERSION = env.BRANCH_NAME
    def USER_DOC = 'https://prod-1-confluence.mufgamericas.com/display/CICDDocs/CICD+Pipelines+User+Documentation#CICDPipelinesUserDocumentation-PrerequisitesPrerequisites'
    BuildConfiguration[] buildConfigurations = [
            new BuildConfiguration(id: "javascript", baseImage: env.BASE_IMAGE_FRONTEND ?: baseImageFrontend, buildOption: env.BUILD_OPTION_FRONTEND ?: "", packagingType: "container"),
    ] as BuildConfiguration[]

    NO_DEPLOYMENT = 'No Deployment'
    CLUSTER_CONFIG_FILE = 'com/mufg/tsi/devsecops/pipelines/openshift.yaml'
    VERACODE_SCRIPTS = 'com/mufg/tsi/devsecops/pipelines/bin/vcode_helper.sh'

    BUILD_GIT_SRC_REPO = "https://bbk.unionbank.com/scm/dsops/groovy_sharedlibs.git"
    BUILD_GIT_BRANCH = env.BUILD_GIT_BRANCH ? env.BUILD_GIT_BRANCH : "master"
    BUILD_CONTEXT_DIR = "resources/docker-build/javascript"

    final Map<String, Map<String, String>> CLUSTER_MAP = new HashMap<>()

    pipeline {
        agent {
            kubernetes {
                cloud "${openshiftCluster}-${openshiftProject}"
                label "jenkins-${openshiftCluster}-${openshiftProject}-${BUILD_NUMBER}"
                //This can't exceed 63 chars
                yaml libraryResource('agent-pod-conf/jenkins-agent-pod-js.yaml')
            }
        }
        options {
            //GLOBAL BUILD OPTIONS
            buildDiscarder(logRotator(numToKeepStr: '13'))
            disableConcurrentBuilds()
            timeout(time: 45, unit: 'MINUTES')
        }

        environment {
            
            // This path is from a emptydir volume primarily for  multiple containers to share files
            ARTIFACTS_DIR = '/var/tmp/artifacts'

            // These are for oc to make outbound connection to the OpenShift cluster URL
            PROXY='http://ub-app-proxy.uboc.com:80'
            HTTP_PROXY="${env.PROXY}"
            HTTPS_PROXY="${env.PROXY}"
            NO_PROXY = "kubernetes.default.svc,opn-console-mmz-uat1.unionbank.com,opn-console-mmz-uat2.opn.unionbank.com"
           
            // Parse the BitBucket Project and Repo from the URL 
            BBK_PROJECT="${env.GIT_URL.split('/')[4]}"
            //BBK_REPO="${(env.GIT_URL.split('/')[5]).split('.')[0]}"
            BBK_REPO="${env.GIT_URL.split('/')[5]}"

            APP_NAME= ("${env.BBK_PROJECT}-${env.BBK_REPO}".toLowerCase()).replace(".", "-")
            BUILD_NAME= ("${APP_NAME}-${BUILD_NUMBER}".toLowerCase()).replace(".", "-")
            OPENSHIFT_CLUSTER_URL = "insecure://kubernetes.default.svc:443"
            OPENSHIFT_TOKEN = credentials("${openshiftCluster}-${openshiftProject}-jenkins")
        }

        parameters {
            choice(name: "Deployment", choices: "${fetchClustersFromOnboardingFile(CLUSTER_MAP)}", description: "Choose the environment to deploy.")
        }

        stages {
            stage("parameterizing") {
                steps {
                    script {
                        if ("${params.Invoke_Parameters}" == "Yes") {
                            currentBuild.result = 'ABORTED'
                            error('DRY RUN COMPLETED. JOB PARAMETERIZED.')
                        }
                    }
                }
            }

            stage('Validate package.json') {
                steps {
                    container('node') {
                        script {
                            // Validate inputs and set build params
                            assert "${GSI}" != null
                            assert "${BBK_PROJECT}" != null
                            assert "${BBK_REPO}" != null

                            try {
                                env.ARTIFACTID = sh (script: "cat package.json | jq -r .name", returnStdout: true).trim()
                                env.VERSION = sh (script: "cat package.json | jq -r .version", returnStdout: true).trim()

                                assert env.ARTIFACTID != null
                                assert env.VERSION != null
                              
                            } catch (AssertionError e) {
                                echo "Invalid package.json - See details at ${USER_DOC}"
                                currentBuild.result = 'FAILURE'
                                throw e
                            }
                            try {
                                assert env.GSI != null
                            } catch (AssertionError e) {
                                echo "Missing critical config parameters at job config"
                                currentBuild.result = 'FAILURE'
                                throw e
                            }
                            env.ARTIFACTFILE = "${env.ARTIFACTID}-${env.VERSION}-${env.BUILD_NUMBER}.zip"

                            if (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) {
                                env.VERSION = "${env.VERSION}" + "${escapeBranch()}"
                                echo "Release version: ${env.VERSION}"
                            } else {
                                echo "Non release version: ${env.VERSION} ${env.BRANCH_NAME}"
                                if (env.TAG_NAME) {
                                    env.VERSION = env.BRANCH_NAME
                                }
                            }
                            echo "version original: ${env.VERSION} "
                            echo "branch ${escapeBranch()}"
                            echo "Build Number: ${env.BUILD_NUMBER}"
                            echo "VERSION: ${env.VERSION}"
                        }
                    }
                }
            }

            stage('Build Binaries') {
                when {
                    not { buildingTag() }
                }
                steps {
                    container('node') {
                        script {
                            withCredentials([
                            usernamePassword(credentialsId: "${nexusCredentialId}",
                                             usernameVariable: 'NEXUS_USERNAME',
                                             passwordVariable: 'NEXUS_PASSWORD')]) {
                                sh (script: """
                                    # Set up npm
                                    npm config set registry ${npmRepo}
                                    npm config set cafile /var/tmp/certs/ca-cert.ca
                                    set +x  # Hide the token from log
                                    echo _auth=\$(echo -n \"${NEXUS_USERNAME}:${NEXUS_PASSWORD}\"|base64) >> /home/node/.npmrc
                                    set -x

                                    # Build and package app
                                    npm install
                                    npm run build
				    npm run coverage
                                    echo ${env.ARTIFACTID} ${ARTIFACTS_DIR} ${env.ARTIFACTFILE} ${APP_NAME} ${BBK_PROJECT} ${env.BBK_REPO}
                                    (cd build && zip -r ${ARTIFACTS_DIR}/${env.ARTIFACTFILE} .)
                                """, returnStdout: true).trim()
                            }
                        }
                    }
                }
            }

            //stage('SonarQube Scan') {
            //    when {
            //        not { buildingTag() }
            //    }
            //    steps {
            //        container('maven') {
            //            script {
            //                try {
            //                    withSonarQubeEnv(sonarQubeEnv ?: DEFAULT_SONAR_ENV) {
            //                        sh "mvn sonar:sonar -P sonar -Dsonar.host.url=${SONAR_HOST_URL} -Dsonar.login=${SONAR_AUTH_TOKEN}"
            //                    }
            //                } catch (error) {
            //                    echo "Failed to perform SonarQube Scan...Ignore error and continue " + error.getMessage()
            //                }
            //            }
            //        }
            //    }
            //}

           stage('Nexus IQ Scan') {
              when {
                  not { buildingTag() }
              }
              steps {
                  container('node') {
                      script {
                          catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE', SECURITY_QUALITY_PASS_FLAG: (SECURITY_QUALITY_PASS_FLAG & false)) {
                            nexusIQScan(
                                IQ_STAGE: (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX ) ? 'release' : 'build',
                                GSI: env.GSI,
                                ARTIFACT_ID: env.ARTIFACTID
                            )
                          }                         
                      }
                  }
              }
           }
           
            //stage('Veracode Scan') {
            //    when {
            //        not { buildingTag() }
            //    }
            //    steps {
            //        container('maven') {
            //            script {
            //                veracodeScan(
            //                        buildType: (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) ? 'release' : 'snapshot',
            //                        gsiCode: POM.properties['gsi-code']
            //                )
            //            }
            //        }
            //    }
            //}

            stage('Publish Artifacts') {
                when {
                  allOf {
                    expression {SECURITY_QUALITY_PASS_FLAG == true}
                    not { buildingTag() }
                  }
                }
                environment {
                    GIT_AUTH = credentials('bitbucket-tagging')
                }
                steps {
                    script {
                        container('jnlp') {
                            if (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) {
                                try {
                                    RELEASE_VERSION = "v${env.VERSION}"

                                    sh "git config --global user.name 'Jenkins Agent'"
                                    sh "git config --global user.email 'unused@us.mufg.jp'"
                                    sh "git tag ${RELEASE_VERSION} -am \"Jenkins release tag\""
                                    sh('''
                                    git config --local credential.helper "!f() { echo username=\\$GIT_AUTH_USR; echo password=\\$GIT_AUTH_PSW; }; f"
                                    git push origin --tags
                                    ''')
                                } catch (error) {
                                    echo "Exception in deploy: " + error.getMessage()
                                    throw error
                                }

                                // Publish artifact to Nexus
                                withCredentials([
                                usernamePassword(credentialsId: "${nexusCredentialId}",
                                                 usernameVariable: 'NEXUS_USERNAME',
                                                 passwordVariable: 'NEXUS_PASSWORD')]) {
    
                                    env.NEXUS_CONTENT_PATH = "${env.GSI}/${BBK_PROJECT}/${BBK_REPO}/${env.VERSION}"
                                    String http_status = sh (script: """
                                        curl -k -u ${NEXUS_USERNAME}:${NEXUS_PASSWORD} -X POST \
                                          ${nexusUrl}/service/rest/v1/components?repository=${nexusArtifactRepo} \
                                          -F raw.directory=/${env.NEXUS_CONTENT_PATH} \
                                          -F raw.asset1=@${ARTIFACTS_DIR}/${env.ARTIFACTFILE} \
                                          -F raw.asset1.filename=${env.ARTIFACTFILE} \
                                          -w %{http_code}
                                        """, returnStdout: true).trim()
    
                                        switch(http_status) { 
                                            case ~/.*204.*/: 
                                               echo "Artifact Uploaded Successfully"
                                           break 
                                        case ~/.*400.*/: 
                                           error("The artifact may have been previously uploaded.")
                                           break 
                                        default: 
                                           error("Unknown status - " + http_status)
                                           break 
                                        }
                                }

                                // Build container and publish to Nexus
                                dir('build-docker-image') {
                                    // Keep buildConfiguration here to align with Java pipeline's implementation
                                    def buildConfiguration = buildConfigurations.find {"javascript"} ?: buildConfigurations[0]

                                    // Get buildconfig and build params from lib resource for oc to create build
                                    data = libraryResource "docker-build/${buildConfiguration.id}/buildconfig_docker.yaml"
                                    writeFile file: "buildconfig_docker.yaml", text: data
                                    data = libraryResource "docker-build/${buildConfiguration.id}/docker-build-params"
                                    writeFile file: "docker-build-params", text: data

                                    ARTIFACT_URL = "${nexusUrl}/repository/${nexusArtifactRepo}/${env.NEXUS_CONTENT_PATH}/${env.ARTIFACTFILE}"
                                    openshift.logLevel(1)
                                    openshift.withCluster( "${OPENSHIFT_CLUSTER_URL}" ) {
                                        openshift.withCredentials( "${OPENSHIFT_TOKEN}" ) {
                                            openshift.withProject( "${openshiftProject}" ) {

                                                BUILD_NAME="${BUILD_TAG.toLowerCase().replace("~", "-")}"
                                                // https://docs.openshift.com/container-platform/3.11/architecture/core_concepts/builds_and_image_streams.html#image-stream-mappings
                                                if (BUILD_NAME.length() > 63){
                                                    BUILD_NAME = BUILD_NAME[0..62]
                                                }

                                                def models = openshift.process("-f", "buildconfig_docker.yaml", \
                                                "-p", "BUILD_NAME=$BUILD_NAME", \
                                                "-p", "GIT_SRC_REPO=${BUILD_GIT_SRC_REPO}", \
                                                "-p", "GIT_BRANCH=${BUILD_GIT_BRANCH}", \
                                                "-p", "CONTEXT_DIR=${BUILD_CONTEXT_DIR}", \
                                                "-p", "FROM_IMAGE_REGISTRY=${dockerRegistryURL}", \
                                                "-p", "FROM_IMAGE=${buildConfiguration.baseImage.split(':')[0]}", \
                                                "-p", "FROM_IMAGE_TAG=${buildConfiguration.baseImage.split(':')[1]}", \
                                                "-p", "TO_IMAGE_REGISTRY=${dockerRegistryURL}", \
                                                "-p", "IMAGE_NAMESPACE=${env.GSI}/", \
                                                "-p", "TO_IMAGE=${env.ARTIFACTID}", \
                                                "-p", "TO_IMAGE_TAG=${RELEASE_VERSION}", \
                                                "-p", "ARTIFACT_URL=${ARTIFACT_URL}", \
                                                "--param-file=docker-build-params"
                                                )
                                                openshift.logLevel(0)
                                                def created = openshift.create( models )
                                                def bc = created.narrow('bc')
                                                def build = bc.startBuild()
                                                build.logs('-f') // This will run to the end of the build
                                                if ( build.object().status.phase == "Failed" ) {
                                                    error("${build.object().status.reason}")
                                                }
                                                echo "Built and Pushed Image - ${build.object().status.outputDockerImageReference}"
                                            }
                                        }
                                    }
                                }
                            } else {
                                echo "Code is not in a deploy branch. Bypassing deployment build."
                            }
                        }
                    }
                }
            }

            stage('Deploy to Target Environment') {
                when {
                  allOf {
                    expression {SECURITY_QUALITY_PASS_FLAG == true}
                  }
                }
                steps {
                    container('jnlp') {
                        script {
                            if ((!(env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX) && !env.TAG_NAME)
                                    || "${NO_DEPLOYMENT}" == "${params.Deployment}"
                                    || "${params.Deployment}" == "") {
                                echo "No deployment."
                            } else {
                                echo "Deployment clusters: ${params.Deployment}"
                                if (env.K8S_DEPLOYMENT_REPOSITORY) {
                                    //assuming deployment repo has the same branch name as environment name except prd for master
                                    assert env.K8S_DEPLOYMENT_CONFIG: "K8S_DEPLOYMENT_CONFIG is required to deploy. Assuming the IMAGE_VERSION will be replaced with current build version"
                                    echo "Deployment config: ${env.K8S_DEPLOYMENT_CONFIG} "
                                    String deploymentName = params.Deployment
                                    Map<String, String> clusterEnv = CLUSTER_MAP.get(deploymentName.trim())
                                    String branchName = clusterEnv.get("environment")
                                    branchName = "prd".equalsIgnoreCase(branchName) ? "master" : branchName
                                    dir('deployment-repo') {
                                        git branch: branchName, url: env.K8S_DEPLOYMENT_REPOSITORY, credentialsId: 'bitbucket'
                                        try {
                                            String clusterURL = getEnvUrl(clusterEnv.get("cluster"))
                                            String project = clusterEnv.get("project")
                                            String cluster = clusterEnv.get("cluster")
                                            String tokenId = cluster + '-' + project + '-jenkins'
                                            String imageTagName = env.IMAGE_VERSION_TAG ?: 'IMAGE_VERSION'

                                            echo "Deployment Name: ${deploymentName}"
                                            echo "clusterEnv: ${clusterEnv}"
                                            echo "Cluster Map: ${CLUSTER_MAP}"
                                            echo "Cluster URL: ${clusterURL}"
                                            echo "TOKEN ID: ${tokenId}"
                                            echo "image version tag: ${imageTagName}"
                                            sh "find . -type f -exec sed -i s/${imageTagName}/${RELEASE_VERSION}/g {} \\;"
                                            withCredentials([string(credentialsId: "${tokenId}", variable: 'TOKEN')]) {
                                                openshift.logLevel(LOG_LEVEL)
                                                applyFile(
                                                        projectName: project,
                                                        deploymentFile: "${env.K8S_DEPLOYMENT_CONFIG}",
                                                        clusterAPI: clusterURL,
                                                        clusterToken: "${TOKEN}"
                                                )
                                            }
                                        } catch (e) {
                                            echo "Error encountered: " + e.getMessage()
                                            throw e
                                        }
                                    }
                                } else {
                                    echo "No deployment repo is specified! No deployment."
                                }
                            }
                        }
                    }
                }
            }
        }
        post {
            always {
                container('jnlp') {
                    script {
                        openshift.logLevel(0)
                        openshift.withCluster( "${OPENSHIFT_CLUSTER_URL}" ) {
                            openshift.withCredentials( "${OPENSHIFT_TOKEN}" ) {
                                openshift.withProject( "${openshiftProject}" ) {
                                    openshift.selector('buildconfig', [app: "${BUILD_NAME}"]).delete()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

def escapeBranch() {
    if (env.BRANCH_NAME =~ /release.*|develop|master/) {
        return "." + "${env.BUILD_NUMBER}"
    }
    return "-" + "${env.BRANCH_NAME}".replace('/', '-') + "." + "${env.BUILD_NUMBER}"
}

def fetchClustersFromOnboardingFile(Map<String, Map<String, String>> clustersMap) {
    List<Map<String, String>> clustersData = Helper.base64DecodeMap(env.OPENSHIFT_APP_PROJECTS)
    echo "OPENSHIFT_APP_PROJECTS: ${clustersData}"
    StringBuffer clusterNames = new StringBuffer()
    for (Map<String, String> map : clustersData) {
        StringBuffer individualName = new StringBuffer()
        for (Map.Entry<String, String> entry : map.entrySet()) {
            individualName.append(entry.getKey()).append(":").append(entry.getValue()).append(" ")
        }
        clustersMap.put(individualName.toString().trim(), map)
        clusterNames.append(individualName.toString()).append("\n")
    }
    clusterNames.append("${NO_DEPLOYMENT}")
    return clusterNames.toString()
}

def getEnvUrl(String envName) {
    def data = libraryResource "${CLUSTER_CONFIG_FILE}"
    return Helper.lookupClusterURL(data, envName)
}
======== k8s_release_pipeline.groovy ========
@Grab('org.yaml:snakeyaml:1.18')
import org.yaml.snakeyaml.Yaml

import com.mufg.tsi.devsecops.pipelines.Helper

/**
  * Most of the following is based on info from the Jenkins blog and documentation: 
  * https://jenkins.io/blog/2017/10/02/pipeline-templates-with-shared-libraries/
  * https://jenkins.io/doc/book/pipeline/shared-libraries/
  */

def call(body) {
    def pipelineParams = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    def LOG_LEVEL = '0'

    EMERGENCY_CM_APPROVERS_FILE = 'change_management/emergency_approvers.yaml'

    pipeline {
        agent {
            kubernetes {
                cloud "${pipelineParams.openshiftCluster}-${pipelineParams.openshiftProject}"
                label "jenkins-${JOB_BASE_NAME}-${BUILD_NUMBER}"    //This can't exceed 63 chars
                yaml libraryResource('agent-pod-conf/jenkins-agent-pod.yaml')
            }
        }

        options {
            //GLOBAL BUILD OPTIONS
            timeout(time: 15, unit: 'MINUTES')
        }

        environment {
          OPENSHIFT_TOKEN = credentials("${OPENSHIFT_TARGET_CLUSTER}-${OPENSHIFT_APP_PROJECT}-jenkins")

          // These are for oc to make outbound connection to the OpenShift cluster URL
          PROXY='http://ub-app-proxy.uboc.com:80'
          HTTP_PROXY="${env.PROXY}"
          HTTPS_PROXY="${env.PROXY}"
        }

        parameters {
            //choice(name: "Deployment", choices: "${fetchClustersFromOnboardingFile(CLUSTER_MAP)}", description: "Choose the environment to deploy.")
            string(name: 'ChangeNumber', defaultValue: '', description: 'Change Management Ticket Number - e.g. CHG1234567. This must be provided for production deployment or the deployment will be denied. For emergency deployment, i.e. in case an emergency change management tickat can\'t be obtained, enter \"emergency\" and reach out to the pipeline operations team for approval.')
        }
    
        stages {
           stage('Gather Deployment Config') {
               steps {
                   container('jnlp') {
                      script {
                          def openshift_conf = libraryResource 'com/mufg/tsi/devsecops/pipelines/openshift.yaml'
                          env.OPENSHIFT_CLUSTER_URL = Helper.lookupClusterURL(openshift_conf, env.OPENSHIFT_TARGET_CLUSTER)
                      }
                   }
               }
           }
           stage('Checkout Application Repo') {
               steps {
                   container('jnlp') {
                       sh "mkdir app-repo"
                       dir ("app-repo") {
                          script {
                              if ( env.LOG_LEVEL ) {
                                  LOG_LEVEL = "${env.LOG_LEVEL}"
                              } 
                          }
                          git branch: "${BBK_REPO_BRANCH}", 
                              url: "${BBK_REPO_URL}",
                              credentialsId: "${pipelineParams.bitbucket_credential_id}"
                       }
                   }
               }
           }
           stage('Change Management Validation') {
               steps {
                    container('python3') {
                        script {
                            //def environment = "${OPENSHIFT_APP_PROJECT}".split('-').last().toLowerCase()
                            def environment = getTargetEnvironment("${OPENSHIFT_APP_PROJECT}")
                            if (environment == "prd" || environment == "cdr") {
                                // if ticket number = emergency, allow for emergency deployment by the pipeline operations team
                                if ( params.ChangeNumber.trim().toLowerCase() == "emergency") {
                                    echo "########## IMPORTANT ##########"
                                    echo "This is an emergency deployment to Production. Explicit approval by the pipeline operations team is required. Please have the operations team work alongside you to approve the deployment."
                                    echo "Emergency deployment should be a last resort to resolve a pressing Production outage in case an emergency change management ticket can't be obtained. A post-event change management ticket must be raised to cover this emergency change."
                                    def userInput = ''
                                    timeout(time: 15, unit: "MINUTES") {
                                        userInput = input(message: 'Pipeline Operator: Do you approve this emergency deployment to Production?',
                                                          ok: 'Approve',
                                                          submitter: getEmergencyCMApprovers(),
                                                          parameters: [
                                                          [$class: 'TextParameterDefinition',
                                                           defaultValue: '',
                                                           description: 'Enter "Approved" literally to approve or the deployment will be aborted.',
                                                           name: '']
                                                          ])
                                    }
                                    if (userInput.trim().toLowerCase() != "approved") {
                                        error "Invalid value for approving emergency deployment. Deployment aborted."
                                    }
                                    echo "########## IMPORTANT ##########"
                                    echo "Emergency deployment approved. Proceed to deployment."
                                } else {
                                    echo "This is a Production Deployment. Validate Change Ticket with CM (ServiceNow)."
                                    try {
                                        validateChangeManagementTicket(
                                            gsiCode: "${env.GSI}",
                                            changeNumber: "${params.ChangeNumber}"
                                        )
                                    //TO-DO - validate cluster/project with labels matching environment
                                    } catch (e) {
                                        //currentBuild.result = "FAILURE"
                                        error "Change Management Validation Failed. See logs for more details.\n${e}"
                                    }
                                }
                            } else {
                                echo "Change Management is not required for ${environment} environment."
                            }
                        }
                    }
                }
           }
           stage('Deploy to Target Environment') {
    	       steps {
                   container('jnlp') {
                       dir ("app-repo") {
                           script {
                               openshift.logLevel("${LOG_LEVEL}".toInteger())
                               openshift.withCluster( "${OPENSHIFT_CLUSTER_URL}" ) {
                                   try {
                                       openshift.withCredentials( "${OPENSHIFT_TOKEN}" ) {
                                           openshift.withProject( "${OPENSHIFT_APP_PROJECT}" ) {
                                               echo """
Deploying from 
Repo: ${BBK_PROJECT}/${BBK_REPO} 
Branch: ${BBK_REPO_BRANCH} 
Deployment config: ${K8S_DEPLOYMENT_CONFIG} 
to 
OpenShift
Cluster: ${OPENSHIFT_TARGET_CLUSTER} at ${openshift.cluster()} 
Project:  ${openshift.project()}
Running as:  ${openshift.raw( "whoami" ).out}
                                               """

                                               def applied = openshift.apply( readFile( "${K8S_DEPLOYMENT_CONFIG}" ) )
                                               echo "Object(s) deployed:\n${applied.out}"
                                           }
                                       }
                                       def environment = getTargetEnvironment("${OPENSHIFT_APP_PROJECT}")
                                       //if (clusterEnv.get("environment") == "prd") {
                                       if (environment == "prd" || environment == "cdr") {
                                           // Preserve build for successful Production deployment
                                           currentBuild.keepLog = true
                                       }
                                   } catch (e) {
                                       echo "Error encountered: ${e}"
                                       throw e
                                   }
                               }
                           }
                       }
                   }
               }
           }
        } 
    }
}

def getEmergencyCMApprovers() {
    def data = libraryResource "${EMERGENCY_CM_APPROVERS_FILE}"
    return Helper.getEmergencyCmApprovers(data)
}

def getTargetEnvironment(String openshiftAppProject) {
    return openshiftAppProject.split('-').last().toLowerCase()
}
======== load_all_pipeline_intake_jobs.groovy ========
/**
  * Most of the following is based on info from the Jenkins blog and documentation: 
  * https://jenkins.io/blog/2017/10/02/pipeline-templates-with-shared-libraries/
  * https://jenkins.io/doc/book/pipeline/shared-libraries/
  * TODO: Evaluate if we should assume role on master so we don't pass any secrets to workers
  */
def call(body) {
    def pipelineParams = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    def conf_files = []

    pipeline {
        agent {
    	    kubernetes {
                cloud "${pipelineParams.openshiftCluster}-${pipelineParams.openshiftProject}"
    	        label "jenkins-${JOB_BASE_NAME}-${BUILD_NUMBER}"    //This can't exceed 63 chars
                yamlFile 'pipelines/agent/jenkins-agent-pod.yaml'
    	    }
        }
        stages {
            stage('Validate Config Inputs') {
                steps {
                    container('jnlp') {
                        dir('pipeline_intake') {
                            // Check out the repo to get current master commit id
                            git url: "${pipelineParams.pipelineIntakeURL}", credentialsId: "${pipelineParams.credentialsId}"

                            script {
                                // Get the list of yaml files to feed configureJenkinsJobs 
                                conf_files = sh (
                                    script: 'ls *.yaml',
                                    returnStdout: true
                                ).trim().readLines()    // readLines() turns a multi-line output to a list
 
                                // Loop thru the files and configure jobs accordingly 
                                for( f in conf_files ) {
                                    println("Configuring Jenkins with file ${f}")
                                    build job: "${pipelineParams.configJobPath}", parameters: [string(name: 'CONF_FILE', value: "$f")]
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
======== log.groovy ========
def info(message) {
    echo "INFO: ${message}"
}

def warning(message) {
    echo "WARNING: ${message}"
}

def error(message) {
    echo "ERROR: ${message}"
}

def status(stepName, status) {
    def statusMessage = status || "Success"
    echo "STATUS: ${stepName} -- ${statusMessage}"
}
======== nexusIQScan.groovy ========
class NexusIQScanInput implements Serializable {
    String IQ_STAGE
    String GSI
    String ARTIFACT_ID
}

def call(Map input) {
    call(new NexusIQScanInput(input))
}

def call(NexusIQScanInput input) {

    script {
        try {
            IQ_STAGE = input.IQ_STAGE
            SELECTED_APP = "${input.GSI}_${input.ARTIFACT_ID}"

            def policyEvaluation = nexusPolicyEvaluation advancedProperties: '', failBuildOnNetworkError: false,
                    iqApplication: manualApplication("${SELECTED_APP}"),
                    iqScanPatterns: [[scanPattern: '**/target/**/*.jar'],
                                     [scanPattern: '**/target/**/*.war'],
                                     [scanPattern: '**/target/**/*.ear'],
                                     [scanPattern: '**/target/**/*.zip'],
                                     [scanPattern: '**/target/**/*.tar.gz'],
                                     [scanPattern: '**/target/**/*.tgz'],
                                     [scanPattern: '**/Podfile.lock'],
                                     [scanPattern: '**/dist/**/*.jar'],
                                     [scanPattern: '**/dist/**/*.war'],
                                     [scanPattern: '**/dist/**/*.ear'],
                                     [scanPattern: '**/dist/**/*.zip'],
                                     [scanPattern: '**/dist/**/*.tar.gz'],
                                     [scanPattern: '**/dist/**/*.tgz'],
                                     [scanPattern: '**/build/**/*.jar'],
                                     [scanPattern: '**/build/**/*.war'],
                                     [scanPattern: '**/build/**/*.ear'],
                                     [scanPattern: '**/build/**/*.zip'],
                                     [scanPattern: '**/build/**/*.tar.gz'],
                                     [scanPattern: '**/build/**/*.tgz'],
                                     [scanPattern: '**/build/**/*.ipa'],
                    ],
                    iqModuleExcludes: [[moduleExclude: '**/.sonar/**'],
                                       [moduleExclude: '**/.gradle/**'],
                    ],
                    iqStage: "${IQ_STAGE}",
                    jobCredentialsId: ''

            println IQ_STAGE + " Nexus Lifecycle Analysis passed: "
            println "${policyEvaluation.applicationCompositionReportUrl}"
        }
        catch (err) {
            echo "Nexus IQ scan (${IQ_STAGE}) failed!================  " + err.getMessage()

            //throw exception so caller can handle changing build/stage status
            throw err
        }
    }
}
======== processPOMFile.groovy ========
#!/usr/bin/env groovy

class ProcessPOMFileInput implements Serializable {
    String pomFile
    String releaseBranches = "release,hotfix,develop,master"
}

def call(Map input) {
    call(new ProcessPOMFileInput(input))
}

def call(ProcessPOMFileInput input) {

    pom = readMavenPom file: input.pomFile

    assert pom.properties['gsi-code']?.trim(): "gsi-cde is required in pom file See details at https://prod-1-confluence.mufgamericas.com/pages/viewpage.action?spaceKey=CICDDocs&title=CICD+Pipelines+User+Documentation#CICDPipelinesUserDocumentation-PrerequisitesPrerequisites"
    env.GSI_CODE = pom.properties['gsi-code']

    env.MVN_VERSION = pom.version
    env.MVN_ARTIFACT_ID = pom.artifactId
    env.APP_VERSION = pom.version
    // if branch is master don't include branch name in version
    // else include branch name in version
    //
    // NOTE: by strict semantic versioning rules the build information should
    //       be appended to version using a + but + is not valid in docker tags
    if (env.BRANCH_NAME == 'master') {
        env.BUILD_VERSION = "v${env.APP_VERSION}.${env.BUILD_ID}"
    } else {
        safeBranchName = env.BRANCH_NAME.replaceAll('/', '_')
        env.BUILD_VERSION = "v${env.APP_VERSION}-${safeBranchName}.${env.BUILD_ID}"
    }
    echo "App Version:   ${env.APP_VERSION}"
    echo "Build Version: ${env.BUILD_VERSION}"

    safeBranchName = env.BRANCH_NAME.replaceAll('/', '_').substring(0, input.lastIndexOf("/"))

    if (input.releaseBranches != null && input.releaseBranches.split(',').contains(safeBranchName)) {
        echo "pom version original: ${pom.version} "
        echo "branch ${safeBranchName} "
        echo "Build Number: ${env.BUILD_NUMBER} "
        env.POM_VERSION = "${pom.version}".replaceAll("-SNAPSHOT", "") + env.BUILD_NUMBER
        echo "Release pom version: ${env.POM_VERSION}"
    } else {
        echo "Non release pom version: ${pom.version}"
        env.POM_VERSION = pom.version
    }

    env.BUILD_VERSION = "v${env.APP_VERSION}-${safeBranchName}.${env.BUILD_ID}"
}

======== puppet_angular_build_pipeline.groovy ========
/**
  * Most of the following is based on info from the Jenkins blog and documentation: 
  * https://jenkins.io/blog/2017/10/02/pipeline-templates-with-shared-libraries/
  * https://jenkins.io/doc/book/pipeline/shared-libraries/
  */
def call(body) {
    def pipelineParams = [:]
    def DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX = /release.*|hotfix.*|develop|master/
    def SECURITY_QUALITY_PASS_FLAG = true
    def IS_PUPPET_DEPLOYED = false
    def IS_NPM_PACKAGE = true
    
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    pipeline {
        agent {
            kubernetes {
                cloud "${pipelineParams.openshift_cluster}-${pipelineParams.openshift_project}"
                label "${JOB_BASE_NAME}-${BUILD_NUMBER}"    //This can't exceed 63 chars
                yaml libraryResource('agent-pod-conf/jenkins-agent-pod-js.yaml')
            }
        }
        options {
            //GLOBAL BUILD OPTIONS
            buildDiscarder(logRotator(numToKeepStr: '8')) 
            //timestamps() // disable for now as it seems to introduce slowness at scrolling console log
            timeout(time: 45, unit: 'MINUTES')
        }

        environment {
          OPENSHIFT_CLUSTER_URL = "insecure://kubernetes.default.svc:443"
          OPENSHIFT_TOKEN = credentials("${pipelineParams.openshift_cluster}-${pipelineParams.openshift_project}-jenkins")

          // This path is from a emptydir volume primarily for multiple containers to share files
          ARTIFACTS_DIR = '/var/tmp/artifacts'

        }
    
        stages {
           stage('Checkout Application Repo') {
               steps {
                   container('node') {
                       sh "mkdir node-build"
                       dir ("node-build") {

                           // BBK_REPO_URL is propagated from the groovy script that create the build job
                           git branch: "${BBK_REPO_BRANCH}", 
                               url: "${BBK_REPO_URL}",
                               credentialsId: "${pipelineParams.bitbucket_credential_id}"
  
                           script {
                               try{
                                   // Set up ENV for querydeploy.sh to consume
                                   GSI = env.GSI
                                   ARTIFACTID = sh (script: "cat package.json | jq -r .name", returnStdout: true).trim()
                                   VERSION = sh (script: "cat package.json | jq -r .version", returnStdout: true).trim()
                                   assert GSI != null
                                   assert ARTIFACTID != null
                                   assert VERSION != null

                                   if (!isReleaseBuild()) {
                                       // Tag on -SNAPSHOT-[BUILD_NUMBER] to suffix to version to support repeatedly building the same version 
                                       ARTIFACT_VERSION = "${VERSION}-SNAPSHOT-${PUPPET_CONFIG_BUILD_NUMBER}"
                                       echo "Set version at package.json to ${ARTIFACT_VERSION}"
                                       sh (script: """
                                           set +x  # Reduce log verbosity
                                           jq --arg version \"$ARTIFACT_VERSION\" '.version = \$version' package.json > /tmp/package.json
                                           mv /tmp/package.json package.json
                                       """, returnStdout: true).trim()
                                   } else {
                                       ARTIFACT_VERSION = "${VERSION}"
                                   }
                                   

                                   // Determine the target deployment platform with Puppet environment
                                   if (fileExists('deployment.yaml')) {
                                       echo "Read Puppet deployment config from deployment.yaml"
                                       def deployConf = readYaml file: 'deployment.yaml'
                                       validateDeploymentYaml(deployConf)

                                       GSI = deployConf['gsi']
                                       PUPPET_SYSTEMID = deployConf['puppet-systemid']
                                       PLATFORM = deployConf.get('puppet-platform-type', 'ews3')
                                       ARTIFACT_EXTENSION = deployConf.get('node-artifact-file-extension', 'zip')

                                       IS_NPM_PACKAGE = (ARTIFACT_EXTENSION == 'zip') ? false : true
                                       IS_PUPPET_DEPLOYED = true
                                   }
                                   // Default to tgz in case package is not intended to be deployed directly thru Puppet
                                   ARTIFACT_EXTENSION = (IS_NPM_PACKAGE == true) ? 'tgz' : ARTIFACT_EXTENSION

                                   ARTIFACTFILE = "${ARTIFACTID}-${ARTIFACT_VERSION}.${ARTIFACT_EXTENSION}"
                                   // This put artifact in app namespace for app level access control on Nexus
                                   NEXUS_CONTENT_PATH = "${GSI}/${BBK_PROJECT}/${BBK_REPO}/${VERSION}"
                               } catch (AssertionError e) {
                                   echo "Invalid package.json - See details at ${pipelineParams.user_doc_url}"
                                   currentBuild.result = 'FAILURE'
                                   throw e
                               }
                               displayBuildDetails()
                           }
                       }
                   }
               }
           }
           stage('Build') {
               steps {
                   container('node') {
                       dir ("node-build") {
                           
                           script {
                               def packageJson = readJSON file: 'package.json'
                               validateBuildCmd(packageJson)

                               withCredentials([
                               usernamePassword(credentialsId: "${pipelineParams.nexus_credential_id}",
                                                usernameVariable: 'NEXUS_USERNAME', 
                                                passwordVariable: 'NEXUS_PASSWORD')]) {
                               sh (script: """
                                   # Set up npm
                                   npm config set registry ${pipelineParams.npm_repo}
                                   npm config set cafile /var/tmp/certs/ca-cert.ca
                                   set +x  # Hide the token from log
                                   echo _auth=\$(echo -n \"${NEXUS_USERNAME}:${NEXUS_PASSWORD}\"|base64) >> /home/node/.npmrc
                                   set -x

                                   # Remove pre-existing build/dist dir. This will be created by the build.
                                   rm -rf build dist

                                   # Install dependencies
                                   npm install 

                                   # Run Build
                                   npm run build
                               """, returnStdout: true).trim()

                               switch(ARTIFACT_EXTENSION) { 
                                   case ~/zip/: 
                                      echo "Package into ${ARTIFACT_EXTENSION}"

                                      // build is for react; dist is for angular
                                      def output_dir = fileExists('build') ? "build" : "dist/${ARTIFACTID}"
                                      sh (script: """
                                          #(cd ${output_dir} && zip -r ${ARTIFACTS_DIR}/${ARTIFACTFILE} .)
                                          (cd ${output_dir} && zip -r ${ARTIFACTFILE} . && cp ${ARTIFACTFILE} ${ARTIFACTS_DIR}/${ARTIFACTFILE})
                                      """, returnStdout: true).trim()
                                      break 
                                   //case ~/tgz/: 
                                   //   echo "Package into ${ARTIFACT_EXTENSION}"

                                   //   sh (script: """
                                   //       npm pack
                                   //       cp ${ARTIFACTID}-${VERSION}.${ARTIFACT_EXTENSION} ${ARTIFACTS_DIR}/${ARTIFACTFILE}
                                   //   """, returnStdout: true).trim()
                                   //   break 
                                   default: 
                                      echo "Package into NPM package"

                                      sh (script: """
                                          OUTDIR=\$(jq -r '.compilerOptions.outDir' tsconfig.json) && cd \$OUTDIR
                                          npm pack
                                          cp ${ARTIFACTFILE} ${ARTIFACTS_DIR}/${ARTIFACTFILE}
                                      """, returnStdout: true).trim()
                                      break 
                                      break 
                               }
                               }
                           }
                       }
                   }
               }
           }
           
           stage('Nexus IQ Scan') {
              when {
                  not { buildingTag() }
              }
              steps {
                  container('node') {
                      script {
                          catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE', SECURITY_QUALITY_PASS_FLAG: (SECURITY_QUALITY_PASS_FLAG & false)) {
                            nexusIQScan(
                                IQ_STAGE: (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX ) ? 'release' : 'build',
                                GSI: GSI,
                                ARTIFACT_ID: ARTIFACTID
                            )
                          }
                      }
                  }
              }
           }
           stage('Publish NPM Package to Nexus') {
                when {
                  allOf {
                    expression {SECURITY_QUALITY_PASS_FLAG == true}
                    not { buildingTag() }
                    expression {IS_NPM_PACKAGE == true}
                  }
                }
               steps {
                   container('node') {
                       dir ("node-build") {
                           script {
                               try {
                                   sh (script: """
                                       npm --registry=${pipelineParams.nexus_url}/repository/${pipelineParams.nexus_npm_repo}/@${GSI}/ publish ${ARTIFACTS_DIR}/${ARTIFACTFILE}
                                   """, returnStdout: true).trim()
                               }
                               catch (Exception e) {
                                   echo "Are you building a RELEASE version that already exist? Update of release artifact is not allowed."
                                   throw e
                               }

                               NPM_TARBALL_URL = sh (script: """
                                                     npm config set registry ${pipelineParams.nexus_url}/repository/${pipelineParams.nexus_npm_repo}
                                                     npm view @${GSI}/${ARTIFACTID} dist.tarball
                                                 """, returnStdout: true).trim()
                           }
                       }
                   }
               }
           }
           stage('Publish JS Artifact to Nexus') {
               when {
                 allOf {
                   expression {SECURITY_QUALITY_PASS_FLAG == true}
                   not { buildingTag() }
                   expression {IS_NPM_PACKAGE == false}
                 }
               }
               steps {
                   container('node') {
                       dir ("node-build") {
                           script {
                               env.GIT_COMMITTER_NAME = "DSO_Jenkins"
                               env.GIT_COMMITTER_EMAIL = "cicd-help@unionbank.com"
                               withCredentials([
                               usernamePassword(credentialsId: "${pipelineParams.nexus_credential_id}",
                                                usernameVariable: 'NEXUS_USERNAME', 
                                                passwordVariable: 'NEXUS_PASSWORD')]) {
                               String http_status = sh (script: """
                                   curl -k -u ${NEXUS_USERNAME}:${NEXUS_PASSWORD} -X POST \
                                     ${pipelineParams.nexus_url}/service/rest/v1/components?repository=${pipelineParams.nexus_artifact_repo} \
                                     -F raw.directory=/${NEXUS_CONTENT_PATH} \
                                     -F raw.asset1=@${ARTIFACTS_DIR}/${ARTIFACTFILE} \
                                     -F raw.asset1.filename=${ARTIFACTFILE} \
                                     -w %{http_code}
                                   """, returnStdout: true).trim()

                                   switch(http_status) { 
                                       case ~/.*204.*/: 
                                          echo "Artifact Uploaded Successfully"
                                          break 
                                       case ~/.*400.*/: 
                                          error("The artifact may have been previously uploaded.")
                                          break 
                                       default: 
                                          error("Unknown status - " + http_status)
                                          break 
                                   }
                               }
                           }
                       }
                   }
               }
           }
           stage('Create Puppet Deployment Config') {
               when {
                 allOf {
                   expression {SECURITY_QUALITY_PASS_FLAG == true}
                   not { buildingTag() }
                   expression {IS_PUPPET_DEPLOYED == true}
                 }
               }
               options { retry(3) }
               steps {
                  container('python') {
                      script {
                      withCredentials([
                          usernamePassword(credentialsId: "${pipelineParams.puppet_stash_credential_id}", 
                                           usernameVariable: 'PE_USERNAME',
                                           passwordVariable: 'PE_PASSWORD') ]) {
                            String artifact_md5 = sh (script: "md5sum ${ARTIFACTS_DIR}/${ARTIFACTFILE} | awk \'{print \$1}\'", returnStdout: true).trim()
                            String artifact_url = (IS_NPM_PACKAGE == true) ? "${NPM_TARBALL_URL}" : "${pipelineParams.nexus_url}/repository/${pipelineParams.nexus_artifact_repo}/${NEXUS_CONTENT_PATH}/${ARTIFACTFILE}"
                            def output = sh (script: """          
                              python pipelines/bins/queuedeploy.py --action=create \
                                --gsi=${GSI} \
                                --bitbucket_project=${BBK_PROJECT} \
                                --bitbucket_repo=${BBK_REPO} \
                                --artifact=${ARTIFACTID} \
                                --app_version=${VERSION} \
                                --runtime=${PLATFORM} \
                                --puppet_systemid=${PUPPET_SYSTEMID} \
                                --jenkins_build_id=${PUPPET_CONFIG_BUILD_NUMBER} \
                                --artifact_md5=$artifact_md5 \
                                --artifact_url=${artifact_url} 2>&1
                               """, returnStdout: true).trim()
                            echo "${output}"
                      }
                      }
                  }
               }
           }
        }
        post {
            success {
                container('jnlp') {
                    script {
                        makeToKeepBuild()
                    }
                }
            }
            unstable {
                container('jnlp') {
                    script {
                        // This should be removed when NexusIQ block unstable build result
                        makeToKeepBuild()
                    }
                }
            }
        }
    }
}

def makeToKeepBuild() {
    if (isReleaseBuild()) {
        // Keep RELEASE build forever -  https://support.cloudbees.com/hc/en-us/articles/218762207-Automatically-Marking-Jenkins-builds-as-keep-forever
        currentBuild.keepLog = true 
        echo "Successful RELEASE build - Mark to Keep Forever"
    }
}

def displayBuildDetails() {
    // Add text to build history display
    if (env.CI_BUILD_TYPE) {
        if (isReleaseBuild()) {
            manager.addShortText("${CI_BUILD_TYPE}", "white", "limegreen", "0px", "limegreen")
        } else { 
            manager.addShortText("${CI_BUILD_TYPE}", "white", "lightskyblue", "0px", "lightskyblue")
        }
    }
    if (VERSION) {
        manager.addShortText("${VERSION}", "white", "darkgray", "0px", "darkgray")
    }
}

def isReleaseBuild() {
    return "${CI_BUILD_TYPE}".contains('RELEASE')
}

def validateBuildCmd(Map packageJson) {

    try {
        // TO-DO revisit regex and further lock down possible commands to run
        //def utilRegex = '^(ng|webpack|react-scripts)(\\s+[a-zA-Z_-])*'
        def utilRegex = '^(ng|webpack|react-scripts|tsc|gulp)\\s*.*'
        assert (packageJson['scripts']['build'] ==~ utilRegex) : "You can only build with approved utilities/modules"
        assert (packageJson['scripts']['build'] !=~ '&&') : "You may not run other commands beyond building with the approved utilities/modules"
    }
    catch (Exception e) {
        echo "npm run build failed schema check: ${packageJson}"
        throw e
    }
}
======== puppet_angular_release_pipeline.groovy ========
/**
  * Most of the following is based on info from the Jenkins blog and documentation: 
  * https://jenkins.io/blog/2017/10/02/pipeline-templates-with-shared-libraries/
  * https://jenkins.io/doc/book/pipeline/shared-libraries/
  */
def call(body) {
    def pipelineParams = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    pipeline {
        agent {
    	    kubernetes {
                cloud "${pipelineParams.openshift_cluster}-${pipelineParams.openshift_project}"
    	        label "${JOB_BASE_NAME}-${BUILD_NUMBER}"    //This can't exceed 63 chars
                yaml libraryResource('agent-pod-conf/jenkins-agent-pod-js.yaml')
    	    }
        }
        options {
            //GLOBAL BUILD OPTIONS
            buildDiscarder(logRotator(numToKeepStr: '8'))
            //timestamps() // disable for now as it seems to introduce slowness at scrolling console log
            timeout(time: 45, unit: 'MINUTES')
        }

        environment {
          OPENSHIFT_CLUSTER_URL = "insecure://kubernetes.default.svc:443"
          OPENSHIFT_TOKEN = credentials("${pipelineParams.openshift_cluster}-${pipelineParams.openshift_project}-jenkins")
        }
    
        stages {
           stage('Checkout Application Repo') {
               steps {
    		       container('node') {
                       sh "mkdir node-build"
                       dir ("node-build") {
                           // The BBK_* environment variables are propagated by the onboarding automation
                           // and set up locally to the build job
                           git branch: "${BBK_REPO_BRANCH}", 
                               url: "${BBK_REPO_URL}",
                               credentialsId: "${pipelineParams.bitbucket_credential_id}"
                       }
                   }
               }
           }
           stage('Validate package.json and deployment.yaml') {
               steps {
    		       container('node') {
                       dir ("node-build") {
                           script {
                               try{
                                   // Set up ENV for querydeploy.sh to consume
                                   ARTIFACTID = sh (script: "cat package.json | jq -r .name", returnStdout: true).trim()
                                   assert ARTIFACTID != null

                                   // Determine the target deployment platform with Puppet environment
                                   echo "Read Puppet deployment config from deployment.yaml"
                                   def deployConf = readYaml file: 'deployment.yaml'
                                   validateDeploymentYaml(deployConf)

                                   GSI = deployConf['gsi']
                                   PUPPET_SYSTEMID = deployConf['puppet-systemid']
                                   PLATFORM = deployConf.get('puppet-platform-type', 'ews3')
                                   ARTIFACT_EXTENSION = deployConf.get('node-artifact-file-extension', 'zip')

                               } catch (AssertionError e) {
                                   echo "Invalid package.json or deployment.yaml - See details at ${pipelineParams.user_doc_url}"
                                   currentBuild.result = 'FAILURE'
                                   throw e
                               }

                               // Block SNAPSHOT build from deploying into non-DEV environment 
                               if (("${CI_BUILD_TYPE}").contains('SNAPSHOT') && !"${PUPPET_DEPLOY_ENV}".contains('dev')) {
                                  error("SNAPSHOT build can only be deployed to DEV environment.")
                               }

                               // Add text to build history display
                               if (("${CI_BUILD_TYPE}").contains('RELEASE')) {
                                   manager.addShortText("${CI_BUILD_TYPE}", "white", "limegreen", "0px", "limegreen")
                               } else { 
                                   manager.addShortText("${CI_BUILD_TYPE}", "white", "lightskyblue", "0px", "lightskyblue")
                               }
                           }
                       }
                   }
               }
           }
           stage('Move yaml in PE deployment repo ') {
                options { retry(3) }
                steps {
    		       container('node') {
                       script {
                           withCredentials([
                               usernamePassword(credentialsId: "${pipelineParams.puppet_stash_credential_id}", 
                                                usernameVariable: 'PE_USERNAME',
                                                passwordVariable: 'PE_PASSWORD') ]) {

                                  def output = sh (script: """
                                      set +x
                                      echo PUPPET_CONFIG_BUILD_NUMBER: ${PUPPET_CONFIG_BUILD_NUMBER}
                                      echo PUPPET_DEPLOY_ENV: ${PUPPET_DEPLOY_ENV}
                                      echo CI_BUILD_TYPE: ${CI_BUILD_TYPE}
                                      pipelines/bins/queuedeploy.sh queue ${PUPPET_SYSTEMID}-${BBK_PROJECT}/${BBK_REPO} \
                                                           ${PUPPET_CONFIG_BUILD_NUMBER}/${PLATFORM} \
                                                           ${PUPPET_SYSTEMID}_${BBK_PROJECT}_${ARTIFACTID} \
                                                           ${PUPPET_DEPLOY_ENV}/${PLATFORM}
                                  """, returnStdout: true).trim()
                                  echo "${output}"
                           }
                       }
                   }
               }
           }
        }
        post {
            success {
                container('jnlp') {
                    script {
                        if (isReleaseBuild()) {
                            // Keep RELEASE build forever -  https://support.cloudbees.com/hc/en-us/articles/218762207-Automatically-Marking-Jenkins-builds-as-keep-forever
                            currentBuild.keepLog = true
                            echo "Successful RELEASE build - Mark to Keep Forever"
                        }
                    }
                }
            }
        }
    }
}


def displayBuildDetails() {
    // Add text to build history display
    if (env.CI_BUILD_TYPE) {
        if (isReleaseBuild()) {
            manager.addShortText("${CI_BUILD_TYPE}", "white", "limegreen", "0px", "limegreen")
        } else { 
            manager.addShortText("${CI_BUILD_TYPE}", "white", "lightskyblue", "0px", "lightskyblue")
        }
    }
    if (env.VERSION) {
        manager.addShortText("${env.VERSION}", "white", "darkgray", "0px", "darkgray")
    }
}

def isReleaseBuild() {
    return "${CI_BUILD_TYPE}".contains('RELEASE')
}
======== python_build_pipeline.groovy ========
/**
  * Most of the following is based on info from the Jenkins blog and documentation: 
  * https://jenkins.io/blog/2017/10/02/pipeline-templates-with-shared-libraries/
  * https://jenkins.io/doc/book/pipeline/shared-libraries/
  */
def call(body) {
    def pipelineParams = [:]
    def DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX = /release.*|hotfix.*|develop|master/
    def SECURITY_QUALITY_PASS_FLAG = true

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    def APP_NAME = ''
    def BUILD_NAME = ''
    def BASE_IMAGE = "${pipelineParams.pythonBaseImage}"
    def PLATFORM = ''
    def ARTIFACT_FILE = ''

    pipeline {
        agent {
    	    kubernetes {
                cloud "${pipelineParams.openshiftCluster}-${pipelineParams.openshiftProject}"
    	        label "jenkins-${JOB_BASE_NAME}-${BUILD_NUMBER}"    //This can't exceed 63 chars
                yamlFile 'pipelines/agent/jenkins-agent-pod.yaml'
    	    }
        }

        environment {
          NEXUS_VERSION = "${pipelineParams.version}"
          NEXUS_PROTOCOL = "${pipelineParams.protocol}"
          NEXUS_URL = "${pipelineParams.artifactRepoURL}"
          NEXUS_REGISTRY_URL = "${pipelineParams.dockerRegistryURL}"
          NEXUS_ARTIFACTS_REPOSITORY = "${pipelineParams.snapshotRepo}"
    
          OPENSHIFT_CLUSTER_URL = "insecure://kubernetes.default.svc:443"
          OPENSHIFT_TOKEN = credentials("${pipelineParams.openshiftCluster}-${pipelineParams.openshiftProject}-jenkins")

          // This path is from a emptydir volume primarily for  multiple containers to share files
          MAVEN_ARTIFACTS = '/var/tmp/maven-artifacts'
          // Per https://access.redhat.com/documentation/en-us/red_hat_jboss_web_server/3.1/html/red_hat_jboss_web_server_for_openshift/jws_on_openshift_get_started
          S2I_DEPLOYMENTS_DIR = "${MAVEN_ARTIFACTS}/deployments"

        }
    
        stages {
           stage('Checkout Application Repo') {
               steps {
                   container('maven') {
                       sh "mkdir python-build"
                       dir ("python-build") {

                           // BBK_REPO_URL is propagated from the groovy script that create the build job
                           git branch: 'master',
                               url: "${BBK_REPO_URL}",
                               credentialsId: 'bitbucket'

                           script {
                               APP_NAME= ("${BBK_PROJECT}-${BBK_REPO}".toLowerCase()).replace(".", "-")
                               BUILD_NAME= ("${APP_NAME}-${BUILD_NUMBER}".toLowerCase()).replace(".", "-")
                             }
                       }
                   }
               }
           }
           stage('Unit Test') {
               steps {
                   container('python-aws') {
                       script {
                           echo "Conducting Test..."
                       }
                   }
               }
           }
           
           stage('Nexus IQ Scan') {
              when {
                  not { buildingTag() }
              }
              steps {
                  container('maven') {
                      script {
                          catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE', SECURITY_QUALITY_PASS_FLAG: (SECURITY_QUALITY_PASS_FLAG & false)) {
                            nexusIQScan(
                                IQ_STAGE: (env.BRANCH_NAME =~ DEDICATED_NAMESPACES_RELEASE_BRANCHES_REGEX ) ? 'release' : 'build',
                                GSI: APP_NAME
                                ARTIFACT_ID: 'python'
                            )
                          }
                      }
                  }
              }
           }

           stage('Security Scan') {
               steps {
                   container('python-aws') {
                       script {
                           echo "Scanning code..."
                       }
                   }
               }
            }
            
            stage('Build Image and Push to Registry') {
                when {
                  allOf {
                    expression {SECURITY_QUALITY_PASS_FLAG == true}
                    not { buildingTag() }
                  }
                }
                steps {
                    container('jnlp') {
                        script {
                            openshift.logLevel(3)
                            openshift.withCluster( "${OPENSHIFT_CLUSTER_URL}" ) {
                                openshift.withCredentials( "${OPENSHIFT_TOKEN}" ) {
                                    openshift.withProject( "${pipelineParams.openshiftProject}" ) {
                                        openshift.newApp("-f", "pipelines/python-buildconfig.yaml", \
                                        "-p", "BUILD_NAME=${BUILD_NAME}",  \
                                        "-p", "GIT_REPO_URL=${BBK_REPO_URL}",  \
                                        "-p", "FROM_IMAGE=${BASE_IMAGE}", \
                                        "-p", "TO_IMAGE=${NEXUS_REGISTRY_URL}/${APP_NAME}:latest")
                                        
                                        openshift.selector("bc", "${BUILD_NAME}").startBuild("--wait=true")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        post {
            always {
                container('jnlp') {
                    echo "Cleaning up/Deleting app from OpenShift..."
                    script {
                        sh "sleep 120"
                        openshift.logLevel(0)
                        openshift.withCluster( "${OPENSHIFT_CLUSTER_URL}" ) {
                            openshift.withCredentials( "${OPENSHIFT_TOKEN}" ) {
                                openshift.withProject( "${pipelineParams.openshiftProject}" ) {
                                    openshift.selector( 'buildconfig', [ app:"${BUILD_NAME}" ] ).delete()
                                }
                            }
                        }
                    }
                }
                echo "See explanation of common errors at https://test-1-confluence.mufgamericas.com/display/CICDDocs/Jenkins+Pipelines+Operations+Runbook#CommonErrors"
            }
        }
    }
}

======== python_build_pipeline_puppet.groovy ========
/**
 * Most of the following is based on info from the Jenkins blog and documentation:
 * https://jenkins.io/blog/2017/10/02/pipeline-templates-with-shared-libraries/
 * https://jenkins.io/doc/book/pipeline/shared-libraries/
 */
def call(
        String openshiftCluster = "",
        String openshiftProject = "",
        String dockerRegistryURL = "",
        String puppet_stash_credential_id = ""
) {
    def ARTIFACT_ID = ''
    def ARTIFACT_FILE = ''
    def ARTIFACT_VERSION = ''
    def ARTIFACT_DIR = "python-build/dist"
    def PLATFORM = ''
    def PUPPET_SYSTEMID = ''
    def BUILD_TYPE = ''
    def SECURITY_QUALITY_PASS_FLAG = true
    def OPENSHIFT_CLUSTER_URL = "insecure://kubernetes.default.svc:443"
    def OPENSHIFT_TOKEN = credentials("${openshiftCluster}-${openshiftProject}-jenkins")

    assert dockerRegistryURL?.trim(): "Docker Registry url is required."

    pipeline {
        agent {
            kubernetes {
                cloud "${openshiftCluster}-${openshiftProject}"
                label "jenkins-agent-pod-python-${openshiftProject}-${BUILD_NUMBER}"
                yaml libraryResource("agent-pod-conf/jenkins-agent-pod-python.yaml")
            }
        }

        options {
            //GLOBAL BUILD OPTIONS
            buildDiscarder(logRotator(numToKeepStr: '8'))
            timeout(time: 45, unit: 'MINUTES')
        }

        environment {
            NEXUS_REGISTRY_URL = "${dockerRegistryURL}"
            // This path is from a emptydir volume primarily for  multiple containers to share files
            ARTIFACTS_DIR = '/var/tmp/artifacts'

            // These are for oc to make outbound connection to the OpenShift cluster URL
            PROXY = 'http://ub-app-proxy.uboc.com:80'
            HTTP_PROXY = "${env.PROXY}"
            HTTPS_PROXY = "${env.PROXY}"
            NO_PROXY = "kubernetes.default.svc"

            OPENSHIFT_CLUSTER_URL = "insecure://kubernetes.default.svc:443"
            OPENSHIFT_TOKEN = credentials("${openshiftCluster}-${openshiftProject}-jenkins")

            PLATFORM = "custom"
        }

        stages {
            stage("parameterizing") {
                steps {
                    script {
                        if ("${params.Invoke_Parameters}" == "Yes") {
                            currentBuild.result = 'ABORTED'
                            error('DRY RUN COMPLETED. JOB PARAMETERIZED.')
                        }
                        PLATFORM = "custom"
                        PUPPET_SYSTEMID = "${env.GSI}"
                        if (("${CI_BUILD_TYPE}").contains('RELEASE')) {
                            BUILD_TYPE = 'release'
                            // Add text to build history display
                            manager.addShortText("${CI_BUILD_TYPE}", "white", "limegreen", "0px", "limegreen")
                        } else {
                            BUILD_TYPE = 'snapshot'
                            // Add text to build history display
                            manager.addShortText("${CI_BUILD_TYPE}", "white", "lightskyblue", "0px", "lightskyblue")
                        }
                    }
                }
            }

            stage('Checkout Application Repo') {
                steps {
                    container('python') {
                        sh "mkdir python-build"
                        dir("python-build") {

                            // BBK_REPO_URL is propagated from the groovy script that create the build job
                            git branch: "${BBK_REPO_BRANCH}",
                                    url: "${BBK_REPO_URL}",
                                    credentialsId: 'bitbucket'
                        }
                    }
                }
            }

            stage('Build Python App') {
                steps {
                    container('python') {
                        dir("python-build") {
                            sh "cat requirements.txt"
                            sh "pip3 install --user -r requirements.txt"
                            script {
                                ARTIFACT_VERSION = sh(script: "python setup.py --version", returnStdout: true).trim()
                                ARTIFACT_ID = sh(script: "python setup.py --name", returnStdout: true).trim()
                                echo ARTIFACT_ID
                            }
                            sh "python setup.py sdist bdist_wheel"
                        }
                        dir("python-build/dist") {
                            script {
                                ARTIFACT_FILE = sh(script: "ls | grep tar.gz", returnStdout: true).trim()
                                echo ARTIFACT_FILE
                            }
                        }
                    }
                }
            }

            stage('Unit Test') {
                steps {
                    container('python') {
                        dir("python-build") {
                            echo "Conducting Unit Tests..."
                        }

                    }
                }
            }

            stage('Nexus IQ Scan') {
                when {
                    not { buildingTag() }
                }
                steps {
                    container('python') {
                        dir("python-build") {
                            script {
                                catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE', SECURITY_QUALITY_PASS_FLAG: (SECURITY_QUALITY_PASS_FLAG & false)) {
                                    nexusIQScan(
                                            IQ_STAGE: (BUILD_TYPE == 'release') ? BUILD_TYPE : 'build',
                                            GSI: env.GSI.toLowerCase(),
                                            ARTIFACT_ID: ARTIFACT_ID
                                    )
                                }
                            }
                        }
                    }
                }
            }

            stage('Veracode Scan') {
                when {
                    not { buildingTag() }
                }
                steps {
                    container('python') {
                        dir("python-build") {
                            script {
                                veracodeScan(
                                        buildType: BUILD_TYPE,
                                        gsiCode: env.GSI.toLowerCase()
                                )
                            }
                        }
                    }
                }
            }

            stage('Upload to Nexus') {
                steps {
                    container('python') {
                        dir("python-build") {
                            script {
                                sh "twine upload --repository nexus dist/*"
                            }
                        }

                    }
                }
            }

            stage('Create Puppet Deployment Config') {
                when {
                    allOf {
//                        expression {SECURITY_QUALITY_PASS_FLAG == true}
                        not { buildingTag() }
//                        expression {IS_PUPPET_DEPLOYED == true}
                    }
                }
                options { retry(3) }
                steps {
                    container('python') {
                        script {
                            withCredentials([
                                    usernamePassword(credentialsId: "${puppet_stash_credential_id}",
                                            usernameVariable: 'PE_USERNAME',
                                            passwordVariable: 'PE_PASSWORD')]) {
                                String artifact_md5 = sh(script: "md5sum ${ARTIFACT_DIR}/${ARTIFACT_FILE} | awk \'{print \$1}\'", returnStdout: true).trim()
                                String artifact_url = "https://nexus.unionbank.com/repository/pypi-hosted/packages/${ARTIFACT_ID}/${ARTIFACT_VERSION}/${ARTIFACT_FILE}"
                                def output = sh(script: """
                              python pipelines/bins/queuedeploy.py --action=create \
                                --gsi=${GSI} \
                                --bitbucket_project=${BBK_PROJECT} \
                                --bitbucket_repo=${BBK_REPO} \
                                --artifact=${ARTIFACT_ID} \
                                --app_version=${ARTIFACT_VERSION} \
                                --runtime=${PLATFORM} \
                                --puppet_systemid=${PUPPET_SYSTEMID} \
                                --jenkins_build_id=${PUPPET_CONFIG_BUILD_NUMBER} \
                                --artifact_md5=$artifact_md5 \
                                --artifact_url=${artifact_url} 2>&1
                               """, returnStdout: true).trim()
                                echo "${output}"
                            }
                        }
                    }
                }
            }
        }
        post {
            always {
                container('python') {
                    script {
                        echo "Clean up Script"

                    }
                }
            }
        }

    }
}
======== python_release_pipeline.groovy ========
/**
  * Most of the following is based on info from the Jenkins blog and documentation: 
  * https://jenkins.io/blog/2017/10/02/pipeline-templates-with-shared-libraries/
  * https://jenkins.io/doc/book/pipeline/shared-libraries/
  */
def call(body) {
    def pipelineParams = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    def APP_NAME = ''
    def BUILD_NAME = ''
    def DEPLOYMENT_TEMPLATE = ''
    
    pipeline {
        agent {
            kubernetes {
                cloud "${pipelineParams.openshiftCluster}-${pipelineParams.openshiftProject}"
                label "jenkins-${JOB_BASE_NAME}-${BUILD_NUMBER}"    //This can't exceed 63 chars
                yamlFile 'pipelines/agent/jenkins-agent-pod.yaml'
            }
        }
        environment {
          // Syntax available at  https://jenkins.io/doc/book/pipeline/jenkinsfile/#for-secret-text-usernames-and-passwords-and-secret-files
          // Folder level secrets
          OPENSHIFT_TOKEN = credentials("${OPENSHIFT_APP_PROJECT}-jenkins")
          LOGLEVEL = '3'
          NEXUS_REGISTRY_URL = "${pipelineParams.dockerRegistryURL}"
          OPENSHIFT_CLUSTER_URL = "insecure://kubernetes.default.svc:443"
   
          DEPLOYMENT_TEMPLATE_PREFIX = "openshift-deployment"
        }
    
        stages {
           stage('Checkout Application Repo') {
               steps {
                   container('jnlp') {
                       sh "mkdir app-repo"
                       dir ("app-repo") {
                           git branch: 'master',
                               url: "${BBK_REPO_URL}",
                               credentialsId: 'bitbucket'
                       }
                   }
               }
           }
           //stage('Change Management Approval Check') {
           //    steps {
           //         container('jnlp') {
           //             script {
           //                 echo "Checking for approval status..."
           //             }
           //         }
           //     }
           //}
           //stage('Pre-Deployment Security Check') {
           //    steps {
           //         container('jnlp') {
           //             script {
           //                 echo "Conducting Scan..."
           //             }
           //         }
           //     }
           //}
          
           stage('Deploy to Target Environment') {
    	       steps {
                   container('maven') {
                       dir ("app-repo") {
                           script {
                               DEPLOYMENT_TEMPLATE = "${DEPLOYMENT_TEMPLATE_PREFIX}-python.yaml"
                               APP_NAME= ("${BBK_PROJECT}-${BBK_REPO}".toLowerCase()).replace(".", "-")
                               BUILD_NAME= ("${APP_NAME}-${BUILD_NUMBER}".toLowerCase()).replace(".", "-")
                           }
                       }
                   }
                   container('jnlp') {
                       script {
                           openshift.logLevel(3)
                           openshift.withCluster( "${OPENSHIFT_CLUSTER_URL}" ) {
                               try {
                                   // When pulling credential by id, the cred must be of type
                                   // "OpenShift Token for OpenShift Client Plugin"
                                   //openshift.withCredentials( "openshift-client-${APP_ID}" ) {
                                   
                                   // OR the cred may be of type "Secret Text" and pass directly in 
                                   openshift.withCredentials( "${OPENSHIFT_TOKEN}" ) {
                                       openshift.withProject( "${OPENSHIFT_APP_PROJECT}" ) {
                                           echo "Running as ID:  ${openshift.raw( "whoami" ).out}"
    
                                           echo "Deploying ${BBK_PROJECT}/${BBK_REPO} to OpenShift project - ${openshift.project()}"
                                           def models = openshift.process( "-f", "pipelines/deployment/${DEPLOYMENT_TEMPLATE}", \
                                               "-p", "APP_NAME=${BUILD_NAME}", \
                                               "-p", "APP_IMAGE=${NEXUS_REGISTRY_URL}/${APP_NAME}:latest", \
                                               "-l", "app=${APP_NAME}", \
                                               "-l", "version=latest", \
                                               "-l", "createdby=jenkins" )
                                           openshift.create(models)
                                       }
                                   }
                               } catch (e) {
                                   "Error encountered: ${e}"
                               }
                           }
                       }
                   }
               }
           }
           stage('Run Integration Test') {
                steps {
                    container('jnlp') {
                        script {
                            openshift.logLevel(3)
                            sh "sleep 15"
                            openshift.withCluster("${OPENSHIFT_CLUSTER_URL}" ) {
                                openshift.withCredentials( "${OPENSHIFT_TOKEN}" ) {
                                    openshift.withProject( "${pipelineParams.openshiftProject}" ) {
                                        script {
                                            //echo 'Connecting to the app via Service which is only accessible from within the cluster...'
                                            //echo sh (
                                            //    script: "curl http://${BUILD_NAME}.${openshift.project()}.svc -v",
                                            //    returnStdout: true
                                            //).trim()

                                            echo 'Connecting to the app via Route which exposes the service outisde of the cluster...'
                                            echo sh (
                                                script: "curl http://${BUILD_NAME}-${openshift.project()}.opn-apps-mz-dev.unionbank.com -kv ",
                                                returnStdout: true
                                            ).trim()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } 
        post {
            always {
                container('jnlp') {
                    sh "sleep 300"
                    echo "Cleaning up/Deleting app from OpenShift..."
                    script {
                        openshift.logLevel(3)
                        openshift.withCluster( "${OPENSHIFT_CLUSTER_URL}" ) {
                            openshift.withCredentials( "${OPENSHIFT_TOKEN}" ) {
                                openshift.withProject( "${pipelineParams.openshiftProject}" ) {
                                    openshift.selector( 'all', [ app:"${BUILD_NAME}" ] ).delete()
                                    openshift.selector( 'route', [ app:"${BUILD_NAME}" ] ).delete()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
======== python_release_pipeline_puppet.groovy ========
/**
 * Most of the following is based on info from the Jenkins blog and documentation:
 * https://jenkins.io/blog/2017/10/02/pipeline-templates-with-shared-libraries/
 * https://jenkins.io/doc/book/pipeline/shared-libraries/
 */
def call(
        String openshiftCluster = "",
        String openshiftProject = "",
        String dockerRegistryURL = "",
        String puppet_stash_credential_id = ""
) {
    def PUPPET_SYSTEMID = ''
    def PLATFORM = ''
    def ARTIFACT_ID = ''
    def OPENSHIFT_CLUSTER_URL = "insecure://kubernetes.default.svc:443"
    def OPENSHIFT_TOKEN = credentials("${openshiftCluster}-${openshiftProject}-jenkins")

    assert dockerRegistryURL?.trim(): "Docker Registry url is required."

    pipeline {
        agent {
            kubernetes {
                cloud "${openshiftCluster}-${openshiftProject}"
                label "jenkins-agent-pod-python-${openshiftProject}-${BUILD_NUMBER}"
                yaml libraryResource("agent-pod-conf/jenkins-agent-pod-python.yaml")
            }
        }

        options {
            //GLOBAL BUILD OPTIONS
            buildDiscarder(logRotator(numToKeepStr: '8'))
            timeout(time: 45, unit: 'MINUTES')
        }

        environment {
            NEXUS_REGISTRY_URL = "${dockerRegistryURL}"

            // These are for oc to make outbound connection to the OpenShift cluster URL
            PROXY = 'http://ub-app-proxy.uboc.com:80'
            HTTP_PROXY = "${env.PROXY}"
            HTTPS_PROXY = "${env.PROXY}"
            NO_PROXY = "kubernetes.default.svc"

            OPENSHIFT_CLUSTER_URL = "insecure://kubernetes.default.svc:443"
            OPENSHIFT_TOKEN = credentials("${openshiftCluster}-${openshiftProject}-jenkins")
        }

        stages {
            stage("parameterizing") {
                steps {
                    script {
                        if ("${params.Invoke_Parameters}" == "Yes") {
                            currentBuild.result = 'ABORTED'
                            error('DRY RUN COMPLETED. JOB PARAMETERIZED.')
                        }
                        PLATFORM = "custom"
                        PUPPET_SYSTEMID = "${env.GSI}"
                    }
                }
            }

            stage('Checkout Application Repo') {
                steps {
                    container('python') {
                        sh "mkdir python-build"
                        dir("python-build") {

                            // BBK_REPO_URL is propagated from the groovy script that create the build job
                            git branch: "${BBK_REPO_BRANCH}",
                                    url: "${BBK_REPO_URL}",
                                    credentialsId: 'bitbucket'
                            script {
                                ARTIFACT_ID = sh(script: "python setup.py --name", returnStdout: true).trim()
                            }
                        }
                    }
                }
            }

            stage('Move yaml in PE deployment repo ') {
                options { retry(3) }
                steps {
                    container('python') {
                        script {
                            withCredentials([
                                    usernamePassword(credentialsId: "${puppet_stash_credential_id}",
                                            usernameVariable: 'PE_USERNAME',
                                            passwordVariable: 'PE_PASSWORD')]) {

                                def output = sh(script: """
                                      #!/bin/bash
                                      set +x
                                      echo PUPPET_CONFIG_BUILD_NUMBER: ${PUPPET_CONFIG_BUILD_NUMBER}
                                      echo PUPPET_DEPLOY_ENV: ${PUPPET_DEPLOY_ENV}
                                      echo CI_BUILD_TYPE: ${CI_BUILD_TYPE}
                                      pipelines/bins/queuedeploy.sh queue ${PUPPET_SYSTEMID}-${BBK_PROJECT}/${BBK_REPO} \
                                                           ${PUPPET_CONFIG_BUILD_NUMBER}/${PLATFORM} \
                                                           ${PUPPET_SYSTEMID}_${BBK_PROJECT}_${ARTIFACT_ID} \
                                                           ${PUPPET_DEPLOY_ENV}/${PLATFORM}
                                  """, returnStdout: true).trim()
                                echo "${output}"
                            }
                        }
                    }
                }
            }
        }
        post {
            always {
                container('python') {
                    script {
                        echo "Clean up Script"

                    }
                }
            }
        }

    }
}
======== release_pipeline.groovy ========
/**
  * Most of the following is based on info from the Jenkins blog and documentation: 
  * https://jenkins.io/blog/2017/10/02/pipeline-templates-with-shared-libraries/
  * https://jenkins.io/doc/book/pipeline/shared-libraries/
  */
def call(body) {
    def pipelineParams = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    def APP_NAME = ''
    def BUILD_NAME = ''
    def DEPLOYMENT_TEMPLATE = ''
    
    pipeline {
        agent {
            kubernetes {
                cloud "${pipelineParams.openshiftCluster}-${pipelineParams.openshiftProject}"
                label "jenkins-${JOB_BASE_NAME}-${BUILD_NUMBER}"    //This can't exceed 63 chars
                yamlFile 'pipelines/agent/jenkins-agent-pod.yaml'
            }
        }

        options {
            //GLOBAL BUILD OPTIONS
            timeout(time: 45, unit: 'MINUTES')
        }

        environment {
          // Syntax available at  https://jenkins.io/doc/book/pipeline/jenkinsfile/#for-secret-text-usernames-and-passwords-and-secret-files
          // Folder level secrets
          OPENSHIFT_TOKEN = credentials("${OPENSHIFT_APP_PROJECT}-jenkins")
          LOGLEVEL = '3'
          NEXUS_REGISTRY_URL = "${pipelineParams.dockerRegistryURL}"
          OPENSHIFT_CLUSTER_URL = "insecure://kubernetes.default.svc:443"
   
          DEPLOYMENT_TEMPLATE_PREFIX = "openshift-deployment"
        }
    
        stages {
           stage('Checkout Application Repo') {
               steps {
                   container('jnlp') {
                       sh "mkdir app-repo"
                       dir ("app-repo") {
                           git branch: 'master',
                               url: "${BBK_REPO_URL}",
                               credentialsId: 'bitbucket'
                       }
                   }
               }
           }
           stage('Change Management Approval Check') {
               steps {
                    container('jnlp') {
                        script {
                            echo "Checking for approval status..."
                        }
                    }
                }
           }
           stage('Pre-Deployment Security Check') {
               steps {
                    container('jnlp') {
                        script {
                            echo "Conducting Scan..."
                        }
                    }
                }
           }
          
           stage('Deploy to Target Environment') {
    	       steps {
                   container('maven') {
                       dir ("app-repo") {
                           script {
                               pom = readMavenPom file: 'pom.xml'
                               try{
                                   assert pom.parent.artifactId != null
                                   assert pom.parent.version != null
                                   assert pom.properties['gsi-code'] != null
                                   assert pom.properties['component-type'] != null
                                   assert pom.properties['revision'] != null
                                   assert pom.properties['changelist'] != null
                               } catch (AssertionError e) {
                                   echo "Invalid pom.xml - See details at https://prod-1-confluence.mufgamericas.com/pages/viewpage.action?spaceKey=CICDDocs&title=CICD+Pipelines+User+Documentation#CICDPipelinesUserDocumentation-PrerequisitesPrerequisites"
                                   currentBuild.result = 'FAILURE'
                                   throw e
                               }

                               // Determine the target deployment platform
                               if ((pom.parent.artifactId).contains('tomcat') && (pom.parent.version).contains('jws')) {
                                   PLATFORM = 'tomcat'
                                   DEPLOYMENT_TEMPLATE = "${DEPLOYMENT_TEMPLATE_PREFIX}-tomcat.yaml"
                               } else { //if ((pom.parent.artifactId).contains('jboss')) {
                                   PLATFORM = 'jboss'
                                   DEPLOYMENT_TEMPLATE = "${DEPLOYMENT_TEMPLATE_PREFIX}-jboss.yaml"
                               }

                               // Determine the type of build for activating profile at the parent pom
                               if (("${pom.properties['changelist']}").contains('SNAPSHOT')) {
                                   BUILD_TYPE = 'snapshot'
                               } else { 
                                   BUILD_TYPE = 'release'
                               }
                               CHANGE_LIST = "-Dchangelist=${pom.properties['changelist']}"

                               env.POM_VERSION = sh (script: "mvn ${CHANGE_LIST} help:evaluate -Dexpression=project.version -DforceStdout -q -e",
                                                     returnStdout: true
                                                    ).trim()
                               APP_NAME= ("${pom.properties['gsi-code']}-${pom.artifactId}-${pom.properties['component-type']}".toLowerCase()).replace(".", "-")
                               BUILD_NAME= ("${APP_NAME}-${BUILD_NUMBER}".toLowerCase()).replace(".", "-")
                           }
                       }
                   }
                   container('jnlp') {
                       script {
                           openshift.logLevel(2)
                           openshift.withCluster( "${OPENSHIFT_CLUSTER_URL}" ) {
                               try {
                                   // When pulling credential by id, the cred must be of type
                                   // "OpenShift Token for OpenShift Client Plugin"
                                   //openshift.withCredentials( "openshift-client-${APP_ID}" ) {
                                   
                                   // OR the cred may be of type "Secret Text" and pass directly in 
                                   openshift.withCredentials( "${OPENSHIFT_TOKEN}" ) {
                                       openshift.withProject( "${OPENSHIFT_APP_PROJECT}" ) {
                                           echo "Running as ID:  ${openshift.raw( "whoami" ).out}"
    
                                           echo "Deploying ${BBK_PROJECT}/${BBK_REPO} to OpenShift project - ${openshift.project()}"
                                           def models = openshift.process( "-f", "pipelines/deployment/${DEPLOYMENT_TEMPLATE}", \
                                               "-p", "APP_NAME=${BUILD_NAME}", \
                                               "-p", "APP_IMAGE=${NEXUS_REGISTRY_URL}/${APP_NAME}:${env.POM_VERSION}", \
                                               "-l", "app=${APP_NAME}", \
                                               "-l", "name=${pom.artifactId}", \
                                               "-l", "version=${env.POM_VERSION}", \
                                               "-l", "createdby=jenkins" )
                                           openshift.create(models)
                                       }
                                   }
                               } catch (e) {
                                   echo "Error encountered: ${e}"
                                   throw e
                               }
    
                           }
                       }
                   }
               }
           }
        } 
    }
}
======== retrievePamServiceAccount.groovy ========
/**
 The function takes PAM Account ID as an argument and returns Credential and Password
 Login and Password for URL request also must be in Jenkins credentials in order to work correctly.
 */
@GrabResolver(name='jenkins', root='http://repo.jenkins-ci.org/public/')
@Grapes([
        @Grab('com.github.groovy-wslite:groovy-wslite:1.1.3')
])

import wslite.rest.RESTClient
import wslite.http.auth.HTTPBasicAuthorization
import groovy.json.JsonSlurper

def call (
        String accountId = ""
) {
    withCredentials ([usernamePassword(credentialsId: 'pamAPI', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
        String Path = "api.php/v1/passwords.json/${accountId}?reason=Other&reasonDetails=A2A"

        def client = new RESTClient("${env.PAM_URL}")
        def path = "${Path}"
        client.authorization = new HTTPBasicAuthorization("${USERNAME}", "${PASSWORD}")
        def response = client.get(path: "${path}").text

        def jsonSlurper = new JsonSlurper()
        def jsonResp = jsonSlurper.parseText("${response}")

        String CredentialId = jsonResp.get("credentialId")

        def envVars = [:]
        envVars['Password'] = jsonResp.get("password")

        return [CredentialId, envVars.Password]
    }
}======== rollout.groovy ========
#!/usr/bin/env groovy

class RolloutInput implements Serializable {
    String deploymentConfigName = ""

    //Optional - Platform
    String clusterAPI           = ""
    String clusterToken         = ""
    String projectName          = ""
}

def call(Map input) {
    call(new RolloutInput(input))
}

def call(RolloutInput input) {
    assert input.deploymentConfigName?.trim() : "Param deploymentConfigName should be defined."

    openshift.withCluster(input.clusterAPI, input.clusterToken) {
        openshift.withProject(input.projectName) {
            echo "Get the Rollout Manager: ${input.deploymentConfigName}"
            def deploymentConfig = openshift.selector('dc', input.deploymentConfigName)
            def rolloutManager   = deploymentConfig.rollout()

            echo "Deploy: ${input.deploymentConfigName}"
            rolloutManager.latest()
    
            echo "Wait for Deployment: ${input.deploymentConfigName}"
            rolloutManager.status("-w")
        }
    }
}======== sonarqubeStaticAnalysis.groovy ========
#!/usr/bin/env groovy

class SonarQubeConfigurationInput implements Serializable {
    //Optional
    String pomFile = "pom.xml"
    String buildServerWebHookName = "jenkins"
    String curlOptions = ""
    String sonarEnv = "sonar"

}

def call() {
    call(new SonarQubeConfigurationInput())
}

def call(Map input) {
    call(new SonarQubeConfigurationInput(input))
}

def call(SonarQubeConfigurationInput input) {

    // make sure build server web hook is available
//    checkForBuildServerWebHook(input)

    // Execute the Maven goal sonar:sonar to attempt to generate
    // the report files.
    // Current one: SonarQube-OpenShiftPoC
    withSonarQubeEnv(input.sonarEnv) {
        try {
            sh "mvn sonar:sonar -f ${input.pomFile} -P sonar -Dsonar.host.url=${SONAR_HOST_URL} -Dsonar.login=${SONAR_AUTH_TOKEN}"
        } catch (error) {
            error error.getMessage()
        }
    }

    // Check the quality gate to make sure 
    // it is in a passing state.
    timeout(time: 10, unit: 'MINUTES') {
        waitForQualityGate abortPipeline: true

        def qualitygate = waitForQualityGate()
        if (qualitygate.status != "OK") {
            error "Pipeline aborted due to quality gate failure: ${qualitygate.status}"
        }
    }
}
//
//def checkForBuildServerWebHook(SonarQubeConfigurationInput input) {
//
//    withSonarQubeEnv(input.sonarEnv) {
//
//        println "Validating webhook with name ${input.buildServerWebHookName} exists..."
//        def retVal = sh(returnStdout: true, script: "curl ${input.curlOptions} -u '${SONAR_AUTH_TOKEN}:' ${SONAR_HOST_URL}/api/webhooks/list")
//        println "Return Value is $retVal"
//
//        def tmpfile = "/tmp/sonarwebhooks-${java.util.UUID.randomUUID()}.json"
//        writeFile file: tmpfile, text: retVal
//
//        def webhooksObj = readJSON file: tmpfile
//        def foundHook = webhooksObj?.webhooks?.find {
//            it.name.equalsIgnoreCase(input.buildServerWebHookName)
//        }
//
//        // webhook was not found
//        // create the webhook - this should be more likely be part
//        // of the sonarqube configuration automation
//        if (foundHook == null) {
//            error "No webhook found with name ${input.buildServerWebHookName}.  Please create one in SonarQube."
//        }
//
//        println "Build Server Webhook found.  Continuing SonarQube analysis."
//
//    }
//
//}
======== sonarqubeStaticAnalysis.txt ========
# SonarQube Static Analysis

This function will validate that a build server webhook has been configured in 
SonarQube, run the sonar:sonar maven goal for the configured pom file, and then 
wait for the quality gate status to be OK.  The pipeline will be stopped if the
webhook has not been configured or the quality gate fails. Be advised that the 
SonarQube deployment @ https://github.com/redhat-cop/containers-quickstarts/tree/master/sonarqube
will create this webhook for you.

Sample usage:

Simplest example that assumes that the pom.xml is at the current working directory
and that webhook has been created with the name 'jenkins':
```
stage {
    steps{
        sonarqubeStaticAnalysis()
    }
}
```

Example with all parameters set:
```
stage {
    steps{
        sonarqubeStaticAnalysis(pomFile: "pom.xml",
                                buildServerWebHookName: "jenkins")
    }
}
```

Include this library by adding this before "pipeline" in your Jenkins:
```
library identifier: "pipeline-library@master", retriever: modernSCM(
  [$class: "GitSCMSource",
   remote: "https://github.com/redhat-cop/pipeline-library.git"])
```
======== tagAssociateAPI.groovy ========
/**
 The function takes three arguments: The Artifact's Environment (for instance: "dev"), The Artifact's Version
 and artifactId.
 It applies three-symbol tag on the Nexus' Artifact and the Openshift Deployment
 */
@GrabResolver(name='jenkins', root='http://repo.jenkins-ci.org/public/')
@Grapes([
        @Grab('com.github.groovy-wslite:groovy-wslite:1.1.3')
])

import wslite.rest.RESTClient
import wslite.http.auth.HTTPBasicAuthorization

def call (
        String ENV = "",
        String version = "",
        String artifactId = ""
) {

    def PROD_TAGS_REGEX = /pro.*|dr|prd|cdr/
    def IGNORE_TAGS_REGEX = /mas|mvp/

    if (ENV =~ IGNORE_TAGS_REGEX) {
        echo "wrong ENV name. Ignoring Tagging."
    } else {
        if (ENV =~ PROD_TAGS_REGEX) {
            TAG = "prd"
        } else{
            TAG = ENV.substring(0,3)
        }

        echo "Nexus URL: ${env.NEXUS_URL}"
        String nexusCredentialId = "nexus-prod-user-token"
        withCredentials([
                usernamePassword(credentialsId: "${nexusCredentialId}",
                        usernameVariable: 'NEXUS_USERNAME',
                        passwordVariable: 'NEXUS_PASSWORD')]) {
            //withCredentials ([usernamePassword(credentialsId: 'mua4c8z-Account', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
            String Path = "service/rest/v1/tags/associate/${TAG}?wait=true&version=*${version}&q=\"${artifactId}\""
            echo "Attaching a \"${TAG}\" tag to the ${artifactId} version ${version}"
            def client = new RESTClient("${env.NEXUS_URL}")
            def path = "${Path}"
            client.authorization = new HTTPBasicAuthorization("${NEXUS_USERNAME}", "${NEXUS_PASSWORD}")
            def response = client.post(
                    path: "${path}",
                    headers:['Accept': 'application/json',
                             'Content-Type': 'application/json',]
            )
            response.responseData
        }
    }
}
======== validateChangeManagementTicket.groovy ========
class ValidateChangeManagementInput implements Serializable {
    String gsiCode
    String changeNumber
}

def call(Map input) {
    call(new ValidateChangeManagementInput(input))
}

def call(ValidateChangeManagementInput input) {

    script {
        def fileName = 'validate_cm_ticket.py'
        def command = libraryResource "change_management/${fileName}"
        writeFile(file: fileName, text: command, encoding: "UTF-8")
        sh "chmod u+x ${fileName}"
        withCredentials([
            usernamePassword(credentialsId: 'servicenow-api', usernameVariable: 'SN_API_USER', passwordVariable: 'SN_API_PASSWORD')]) {
                sh "python ${fileName} --gsi=${input.gsiCode} --change_number=${input.changeNumber} --environment=production"
        }
    }
}
======== validateDeploymentYaml.groovy ========
#!/usr/bin/env groovy

def call(Map input) {

    // TO-DO - use an actual schema validator and externalize this code as a common function
    // This can only check required fields and not optional ones

         //"node-artifact-file-extension": ~/tgz|zip/
    def schema = [
         //"puppet-platform-type": ~/custom|jboss.*|ews.*|tomcat|utility|nodejs/
         "puppet-systemid": ~/^\w+([_-]\w+)*$/,
         "gsi": ~/[a-z0-9]{3,4}/
     ]
     schema.each { key, regex ->
         assert input[key]?.trim() : "${schema[key]} is required"
         assert (input[key] ==~ schema[key]) : "${key}: ${input[key]} doesn't match ${schema[key]}"
     }
}
======== validate_pullrequest.groovy ========
/**
  * Most of the following is based on info from the Jenkins blog and documentation: 
  * https://jenkins.io/blog/2017/10/02/pipeline-templates-with-shared-libraries/
  * https://jenkins.io/doc/book/pipeline/shared-libraries/
  * TODO: Evaluate if we should assume role on master so we don't pass any secrets to workers
  */
def call(body) {
    def pipelineParams = [:]

    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    def conf_files = []

    pipeline {
        agent {
    	    kubernetes {
                cloud "${pipelineParams.openshiftCluster}-${pipelineParams.openshiftProject}"
    	        label "jenkins-${JOB_BASE_NAME}-${BUILD_NUMBER}"    //This can't exceed 63 chars
                yaml libraryResource('agent-pod-conf/jenkins-agent-pod.yaml')
    	    }
        }
        stages {
            stage('Validate Config Inputs') {
                steps {
                    container('python-aws') {
                        dir('pipeline_intake') {
                            // Check out the repo to get current master commit id
                            git url: "https://bbk.unionbank.com/scm/dsops/pipeline_intake.git", credentialsId: "bitbucket"
                        }
                        script {
                          // Get the list of files that changed to feed the schema check
                          changed_files = sh (
                              script: 'git diff --name-only $(git --git-dir=./pipeline_intake/.git rev-parse HEAD) $(git rev-parse HEAD)',
                              returnStdout: true
                          ).trim().readLines()    // readLines() turns a multi-line output to a list

                          for( f in changed_files ) {
                              println("changed: $f")
                              if ( "$f" ==~ '^[a-z0-9][a-z0-9_-]*\\.yaml$' ) {
                                  println("Yaml file name passed regex check")
                                  if ( !fileExists("$f") ) return    // Config file was deleted; skip schema check
                                  dir('jenkins') {
                                      // Check out the jenkins repo to get the validation code
                                      git url: "${pipelineParams.pipeline_repo_url}", 
                                          branch: "${pipelineParams.pipeline_repo_branch}",
                                          credentialsId: "${pipelineParams.bitbucket_credential_id}"
                                  }
                                  echo sh (
                                    // Run validation code in the jenkins repo
                                    script: ". /venv/bin/activate && python3.7 jenkins/pipelines/onboarding/bin/python/src/pipeline_intake/validate_config.py --onboarding_file=$f",
                                    returnStdout: true
                                  ).trim()

                                  conf_files << "$f"
                              } else {
                                  println("Failed regex check")
                                  error "You may not modify content beyond the root dir of this repo."
                              }
                          }
                        }
                    }
                }
            }
            stage('Approve Pull Request') {
                steps {
                    container('python-aws') {
                        println("Pause for 90 seconds. Please review the PR in BitBucket. The PR must be approved and merged for subsequent provisioning steps to work! If you can't complete in 90 seconds, re-run this job again before you approve/merge in BitBucket.")
                        sh 'sleep 90'
                        println("Pull Request should be approved by now. Proceed to the rest of the provisioning steps.")
                    }
                }
            }
            stage('Process Jenkins') {
                steps {
                    script{
                        for( f in conf_files ) {
                            println("Configuring Jenkins with file ${f}")
                            build job: '/admin_utils/jobs/configureJenkinsJobs', parameters: [string(name: 'CONF_FILE', value: "$f")]
                        }
                    }
                }
            }
            //stage('Process OpenShift') {
            //    steps {
            //        script{
            //            for( f in conf_files ) {
            //                println("Configuring OpenShift with file ${f}")
            //                build job: '/admin_utils/jobs/configureOpenShiftProject', parameters: [string(name: 'CONF_FILE', value: "$f")]
            //            }
            //        }
            //    }
            //}
        }
    }
}
======== veracodeScan.groovy ========
class VeracodeScanInput implements Serializable {
    String gsiCode
    String buildType
}

def call(Map input) {
    call(new VeracodeScanInput(input))
}

def call(VeracodeScanInput input) {

    script {
        def fileName = 'vcode_helper.sh'
        def command = libraryResource "com/mufg/tsi/devsecops/pipelines/bin/${fileName}"
        writeFile(file: fileName, text: command, encoding: "UTF-8")
        sh "chmod u+x ${fileName}"
        sh "/usr/local/bin/set-user-id"
        withCredentials([
                sshUserPrivateKey(credentialsId: 'abeadm_sshkey_for_build', keyFileVariable: 'SSH_KEY_FILE', usernameVariable: 'SSH_USER'),
                usernamePassword(credentialsId: 'veracode-upload-token', usernameVariable: 'VERACODE_USER', passwordVariable: 'VERACODE_TOKEN')]) {
            sh "./${fileName} \"${input.gsiCode}\" ${input.buildType}"
        }
    }
}
======== wso2_app_creation_with_keys_pipeline.groovy ========
/**
 * Most of the following is based on info from the Jenkins blog and documentation:
 * https://jenkins.io/blog/2017/10/02/pipeline-templates-with-shared-libraries/
 * https://jenkins.io/doc/book/pipeline/shared-libraries/
 */
import com.mufg.tsi.devsecops.pipelines.Helper

class BuildConfiguration {
    def id = ""
    def baseImage = ""
    def buildOption = ""
    def packagingType = ""
}

def call(
        String openshiftCluster = "",
        String openshiftProject = "",
        String nexusCredentialId = "nexus-user-token",
		String artifactVersion="1.0.1-SNAPSHOT",
        String nexusHost="nexus.unionbank.com",
        String nexusArtifactPath="repository/maven-snapshots/com/mufg/eip/promote/api/bbk-api-promotion-sdk/1.0.1-SNAPSHOT/bbk-api-promotion-sdk-1.0.1-20200926.010307-29.jar"
       
       
        
) {

    assert openshiftCluster?.trim(): "openshift Cluster is required."
    assert openshiftProject?.trim(): "openshift Project is required."
	
    def SECURITY_QUALITY_PASS_FLAG = true
	def OPEN_LEGACY_API_PROMOTION_PASS_FLAG = true
    def USER_DOC = 'https://prod-1-confluence.mufgamericas.com/display/CICDDocs/CICD+Pipelines+User+Documentation'
    EMERGENCY_CM_APPROVERS_FILE = 'change_management/emergency_approvers.yaml'

    pipeline {
        agent {
            kubernetes {
                cloud "${openshiftCluster}-${openshiftProject}"
                label "jenkins-${openshiftCluster}-${openshiftProject}-${BUILD_NUMBER}"
                //This can't exceed 63 chars
                yaml libraryResource('agent-pod-conf/jenkins-agent-pod-api-promotion.yaml')
            }
        }
        options {
            //GLOBAL BUILD OPTIONS
            buildDiscarder(logRotator(numToKeepStr: '13'))
            disableConcurrentBuilds()
            timeout(time: 45, unit: 'MINUTES')
        }

        environment {
            NEXUS_REGISTRY_URL = "${dockerRegistryURL}"
            // This path is from a emptydir volume primarily for  multiple containers to share files
            MAVEN_ARTIFACTS = '/var/tmp/maven-artifacts'

            // These are for oc to make outbound connection to the OpenShift cluster URL
            PROXY='http://ub-app-proxy.uboc.com:80'
            HTTP_PROXY="${env.PROXY}"
            http_proxy="${env.PROXY}"
            https_proxy="${env.PROXY}"
            HTTPS_PROXY="${env.PROXY}"
            NO_PROXY=".eip-apps-aws-prd-east.opn.unionbank.com, .eip-apps-aws-prd-west.opn.unionbank.com, nexus-dev.unionbank.com, nexus.unionbank.com, .eip-apps-aws-uat.opn.unionbank.com"
        }

		parameters {
			choice(name: "Target", choices: ['SBX', 'DEV', 'SIT', 'TST', 'PTE', 'UAT', 'PRD', 'CDR'], description: "Choose the Target environment to import the API.")
			string(name: 'ChangeNumber', defaultValue: '', description: 'Change Management Ticket Number - e.g. CHG1234567. This must be provided for production deployment or the deployment will be denied. For emergency deployment, i.e. in case an emergency change management ticket can\'t be obtained, enter \"emergency\" and reach out to the pipeline operations team for approval.')
		}
		
		
        stages {
        	stage('Change Management Validation') {
                when {
                  allOf {
                    expression {SECURITY_QUALITY_PASS_FLAG == true}
                  }
                }
                steps {
                   container('python3') {
                       script {
                       		echo "In Change Management Validation Step"
                               echo "Target Env Selected: ${params.Target}"
                               String environment = "${params.Target}"

                               if ("${environment}" == "PRD" || "${environment}" == "CDR") {
                                   // if ticket number = emergency, allow for emergency deployment by the pipeline operations team
                                   if ( params.ChangeNumber.trim().toLowerCase() == "emergency") {
                                       echo "########## IMPORTANT ##########"
                                       echo "This is an emergency deployment to Production. Explicit approval by the pipeline operations team is required. Please have the operations team work alongside you to approve the deployment."
                                       echo "Emergency deployment should be a last resort to resolve a pressing Production outage in case an emergency change management ticket can't be obtained. A post-event change management ticket must be raised to cover this emergency change."
                                       def userInput = ''
                                       timeout(time: 15, unit: "MINUTES") {
                                           userInput = input(message: 'Pipeline Operator: Do you approve this emergency deployment to Production?', 
                                                             ok: 'Approve',
                                                             submitter: getEmergencyCMApprovers(),
                                                             parameters: [
                                                             [$class: 'TextParameterDefinition', 
                                                              defaultValue: '', 
                                                              description: 'Enter "Approved" literally to approve or the deployment will be aborted.', 
                                                              name: '']
                                                             ])
                                       } 
                                       if (userInput.trim().toLowerCase() != "approved") { 
										   OPEN_LEGACY_API_PROMOTION_PASS_FLAG = false
                                           error "Invalid value for approving emergency deployment. Deployment aborted."
				
                                       }
                                       echo "########## IMPORTANT ##########"
                                       echo "Emergency deployment approved. Proceed to deployment."
                                   } else {
                                       echo "This is a Production Deployment. Validate Change Ticket with CM (ServiceNow)."
                                       try {
                                           validateChangeManagementTicket(
                                               gsiCode: "${env.GSI}",
                                               changeNumber: "${params.ChangeNumber}"
                                           )
                                       //TO-DO - validate cluster/project with labels matching environment
                                       } catch (e) {
                                           //currentBuild.result = "FAILURE"
										   OPEN_LEGACY_API_PROMOTION_PASS_FLAG = false
                                           error "Change Management Validation Failed. See logs for more details.\n${e}"
                                       }
                                   } 
                               } else {
                                   echo "Change Management is not required for ${environment} environment."
                               }
                       }
                   }
                }
            }
            stage("WSO2 App Creation") {
                steps {
                    container('wso32') {
                        script {
                            def fileName = 'create-app-withKeys.sh'
                            def command = libraryResource "wso2-app-creation/scripts/${fileName}"
                            writeFile(file: fileName, text: command, encoding: "UTF-8")
                            sh "chmod u+x ${fileName}"
                            withCredentials([
                                usernamePassword(credentialsId: 'api-promotion-epam-nonprd',
                                    usernameVariable: 'EPAM_NONPRD_USER',
                                    passwordVariable: 'EPAM_NONPRD_PASSWORD'),
                                usernamePassword(credentialsId: 'api-promotion-epam-prd',
                                    usernameVariable: 'EPAM_PRD_USER',
                                    passwordVariable: 'EPAM_PRD_PASSWORD')
                                ]) {
                                   
                                    sh "./create-app-withKeys.sh ${params.Target}"
                                }
                            }
                        
                    }
                }
            }
        }
        post {
            success { 
                script {
                    String environment = "${params.Target}"
                    if ("${environment}" == "PRD" || "${environment}" == "CDR") {
                        // Preserve build for successful Production deployment
                        currentBuild.keepLog = true
                    }
                }
            }
        }
    }
}

def escapeBranch() {
    if (env.BRANCH_NAME =~ /release.*|develop|master/) {
        return "." + "${env.BUILD_NUMBER}"
    }
    return "-" + "${env.BRANCH_NAME}".replace('/', '-') + "." + "${env.BUILD_NUMBER}"
}

def fetchAllowedSourceEnvironments() {
    StringBuffer allowedSourceEnv = new StringBuffer()
    if ("${env.BRANCH_NAME}" == "master") {
        allowedSourceEnv.append("CDR")
    }
    else if ("${env.BRANCH_NAME}" =~ /release.*/) {
        allowedSourceEnv.append("DEV").append("\n").append("UAT")
    }
    else {
        allowedSourceEnv.append("SBX").append("\n").append("DEV").append("\n").append("SIT").append("\n").append("TST").append("\n").append("PTE")
    }
    return allowedSourceEnv.toString()

}

def fetchAllowedTargetEnvironments(){
    StringBuffer allowedTargetEnv = new StringBuffer()
    if ("${env.BRANCH_NAME}" == "master") {
        allowedTargetEnv.append("PRD")
    }
    else if ("${env.BRANCH_NAME}" =~ /release.*/) {
        allowedTargetEnv.append("CDR")
    }
    else {
        allowedTargetEnv.append("SBX").append("\n").append("DEV").append("\n").append("SIT").append("\n").append("TST").append("\n").append("PTE")
    }
    return allowedTargetEnv.toString()
}

def getEmergencyCMApprovers() {
    def data = libraryResource "${EMERGENCY_CM_APPROVERS_FILE}"
    return Helper.getEmergencyCmApprovers(data)
}
======== validate_cm_ticket.py ========
import os
import sys
import argparse
import datetime
import requests
import pprint

"""
This script validates if a (ServiceNow) Change Management ticket is valid to justify a Production deployment. 
It takes the application GSI, a chanage ticket number, and the target environment as input. CM is only required
for Production deployment at the moment. Time windows being approved is also taken into account in the validation.

The script expects the following environment variables to be set:
SN_API_USER     - The username for the ServiceNow API
SN_API_PASSWORD - The password for the ServiceNow API

ServiceNow Table API Doc - https://developer.servicenow.com/dev.do#!/reference/api/orlando/rest/c_TableAPI
"""

def is_cm_approved(ticket):
  return ticket['approval']['value'] == 'approved'

def is_within_change_window(ticket):

  # Override time for testing
  #ticket['start_date']['value'] = '2020-08-08 10:00:00'
  #ticket['end_date']['value'] = '2020-08-11 16:00:00'

  # all time in ServiceNow is stored in UTC 
  utc_start_time = datetime.datetime.strptime(ticket['start_date']['value'], '%Y-%m-%d %H:%M:%S').replace(tzinfo=datetime.timezone.utc)
  utc_end_time = datetime.datetime.strptime(ticket['end_date']['value'], '%Y-%m-%d %H:%M:%S').replace(tzinfo=datetime.timezone.utc)
  utc_now = datetime.datetime.now(datetime.timezone.utc) 

  print("The approved change window per {} is between (UTC) {} and {}. Current (UTC) time is {}.".format(ticket['number']['value'], 
                                                                                                         utc_start_time, 
                                                                                                         utc_end_time, 
                                                                                                         utc_now))
  return (utc_start_time < utc_now < utc_end_time)

def is_gsi_match(deploy_request, ticket):
    assert ticket['u_business_app_gsi_id']['value'], "The change ticket doesn't specify a GSI."
    print("This Jenkins deployment job is configured for {}. Change ticket {} is for {}.".format(deploy_request['gsi'].upper(),
                                                                                                 deploy_request['change_number'],
                                                                                                 ticket['u_business_app_gsi_id']['value'].upper()))
    return deploy_request['gsi'].lower() == ticket['u_business_app_gsi_id']['value'].lower()

def get_change_ticket(deploy_request):
  # Sample URL
  # https://mubdev.service-now.com/api/now/table/change_request?&sysparm_limit=1&sysparm_display_value=all&number=CHG0038496&sysparm_fields=number,start_date,end_date,approval,sys_tags,u_change_owner

  assert deploy_request['change_number'].startswith("CHG"), "Invalid Ticket Number - Change Ticket starts with \"CHG\" follow by a 7-digit number."

  user = os.environ.get('SN_API_USER')
  pwd = os.environ.get('SN_API_PASSWORD')

  api_url = "https://{}/api/now/table/change_request".format(deploy_request['servicenow_api_hostname'])
  
  # fields to retrieve from ServiceNow
  sysparm_fields = [
    "number",                  # Change Management ticket number
    "start_date",              # Planned start date/time 
    "end_date",                # Planned end date/time
    "approval",                # Approval status
    "u_business_app_gsi_id",   # Application GSI for the change
    "u_change_owner",          # The owner of the change
    "sys_tags",                # Tags associated with the change
  ]
  
  # List of params to put into query string
  sysparms = {
    "sysparm_limit": "1",                          # retrieve only a single item -  the ticket being looked at
    "sysparm_display_value": "all",                # get both display and actual value - display = user friendly format and actualy = what's being stored in SN
    "number": deploy_request['change_number'],     # the change ticket number to look at concerning the current deployment request
    "sysparm_fields": ",".join(sysparm_fields),    # the fields to extract from the change ticket 
  }
  
  # Set headers to retrieve json
  headers = {"Accept":"application/json"}
  response = requests.get(api_url, auth=(user, pwd), headers=headers, params=sysparms)
  
  # Check for HTTP codes other than 200
  assert response.status_code == 200, "Unable to retrieve CM ticket from ServiceNow. status: {}, response: {}, url: {}".format(response.status_code, response.text, response.url)
  # Make sure we get a valid ticket
  assert len(response.json()['result']) == 1, "Invalid Change Management Ticket. \"{}\" doesn't exist".format(deploy_request['change_number'])

  # Decode the JSON response into a dictionary
  # We query against a single ticket. It's expected the first element in the result is our ticket.
  return response.json()['result'][0]

def wrap_output_with_poundsigns(message):
  # Intented to wrap standard validation output or exception message with pound sign to increase visibility in pipeline output.
  print("#####################################################################################")
  print("CHANGE MANAGEMENT VALIDATION")
  print(message)
  print("#####################################################################################")

if __name__ == '__main__':
  parser = argparse.ArgumentParser(description='Validate Change Management (ServiceNow) Ticket')
  
  parser.add_argument('--gsi', 
                      type=str,
                      required=True,
                      help='The GSI of the application, from Jenkins perspective, to be deployed.')
  parser.add_argument('--change_number', 
                      type=str,
                      required=True,
                      help='Change management ticket number for the deployment.')
  parser.add_argument('--environment', 
                      type=str,
                      required=True,
                      help='Target environment to receive deployment.')
  parser.add_argument('--servicenow_api_hostname', 
                      type=str,
                      required=False,
                      default="mub.service-now.com",
                      help='Hostname for ServiceNow API')

  args = parser.parse_args()
  deploy_request = {
    "gsi": args.gsi,
    "change_number": args.change_number,
    "environment": args.environment,
    "servicenow_api_hostname": args.servicenow_api_hostname,
  }

  try:
    ticket = get_change_ticket(deploy_request)
    # TO-DO - add debug flag for printing ticket details
    #pp = pprint.PrettyPrinter(indent=6)
    #pp.pprint(ticket)

    assert is_gsi_match(deploy_request, ticket), "The change ticket is not for this application."
    assert is_cm_approved(ticket), "Change ticket {} is not yet approved.".format(deploy_request['change_number'])
    assert is_within_change_window(ticket), "You may not perform change outside of the approved time window."
  except AssertionError as error:
    wrap_output_with_poundsigns("ERROR: {}".format(error))
    sys.exit(1)

  wrap_output_with_poundsigns("{} is approved for {} to deploy into the {} environment.".format(deploy_request['change_number'], deploy_request['gsi'].upper(), deploy_request['environment']))
