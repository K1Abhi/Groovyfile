updateGitlabCommitStatus name: 'Jenkins', state: 'pending'

// include https://gitlab.com/test/devops/jenkins_pipeline_libraries/ libraries
@Library('DevOps') _

pipeline {
    agent {
        label'k8s'
    }
    environment {
        INSTANTLY_AVAILABLE_RESULTS = true
    }
    options {
        timestamps()
    }
    stages {
        stage("Checkout") {
            steps {
                script {
                    cleanWs()
                    gitHelper.checkout('clone') //Clone FCC repository
                    dir('module') {
                        env.release = 'master'
                        def props = readProperties file: '.jenkins_gitlab'
                        env.JDK_VERSION = props.java_version == '17' ? 'OPENJDK17' : 'OPENJDK8U191'
                        env.JAVA_MODULE_OPTIONS_ENABLED = props.java_version == '17' ? true : false
                        env.FITNESSE_JAVA_VERSION = props.fitnesse_java_version
                        env.FITNESSE_JDK_VERSION = props.fitnesse_java_version == '17' ? 'OPENJDK17' : 'OPENJDK8U191'

                        env.PARENT_FCC_VERSION = versionsHelper.getNextVersion(gitlabSourceBranch)
                        sh "git tag -f v${PARENT_FCC_VERSION}"
                        LAST_GREEN_TAG = gitHelper method: "getTags", prefix: "last_green_${release}"
                        if ("$LAST_GREEN_TAG" == "[]") {
                            CHANGED_FILES = "ALL"
                            echo "Going to build $CHANGED_FILES modules, can't find tag last_green_${release} in gitlab"
                        } else {
                            CHANGED_FILES = gitHelper method: "getListOfChangedFiles", from: "last_green_${release}", to: "${gitlabAfter}"
                            echo "${CHANGED_FILES}"
                        }
                        currentBuild.description = ""
                    }
                    dir("scripts"){
                        git branch: "SITE_19.24", url: "git@gitlab.code.com:devops/ci-scripts.git", credentialsId: 'git_gitlab.code.com'
                    }
                    dir("ci-definitions"){
                        git branch: "${release}", url: "git@gitlab.com:test/devops/ci-definitions.git", credentialsId: 'git_gitlab.code.com'
                    }
                    stash name: "ci-definitions", includes: "ci-definitions/**"
                    dir("fcc-tools"){
                        git branch: "master", url: "git@gitlab.com:test/CAP/fcc-tools", credentialsId: 'git_gitlab.code.com'
                    }
                    stash name: "fcc-tools", includes: "fcc-tools/**"
                    dir("soap-tests") {
                        git branch: "${gitlabSourceBranch}", url: "git@gitlab.com:test/CAP/Catalog_SoapUI_tests", credentialsId: 'git_gitlab.code.com'
                    }
                    stash name: "soap-tests", includes: "soap-tests/**"
                }
                    updateGitlabCommitStatus name: 'Jenkins', state: 'running'
            }
        }
        stage("FCC Parent Build") {
            steps {
                dir('module') {
                    withMaven(mavenLocalRepo: '.repository', jdk: JDK_VERSION, maven: 'MAVEN_3.5.0', options: [artifactsPublisher(disabled: true)]) {
                        sh "mvn build-helper:parse-version versions:set -DnewVersion=${PARENT_FCC_VERSION} -B"
                        sh "mvn -f pom.xml clean install deploy:deploy -N -e -B"
                    }
                }
            }
        }
        stage("FCC Common Build") {
            steps {
                dir('module'){
                    withMaven(mavenLocalRepo: '.repository', jdk: JDK_VERSION, maven: 'MAVEN_3.5.0', options: [artifactsPublisher(disabled: true)]) {
                        sh "mvn -f Common/pom.xml install -U -e deploy:deploy -DdeployAtEnd=true -P jenkins -B"
                    }
                }
            }
        }
        stage("FCC Catalog Domain API Build") {
            steps {
                dir('module'){
                    withMaven(mavenLocalRepo: '.repository', jdk: JDK_VERSION, maven: 'MAVEN_3.5.0', options: [artifactsPublisher(disabled: true)]) {
                        sh "mvn -f CatalogDomainAPI/pom.xml install  -U -e deploy:deploy -DdeployAtEnd=true -P jenkins -B"
                    }
                }
            }
        }
        stage("FCC Store Domain Impl Build") {
            steps {
                dir('module'){
                    withMaven(mavenLocalRepo: '.repository', jdk: JDK_VERSION, maven: 'MAVEN_3.5.0', options: [artifactsPublisher(disabled: true)]) {
                        sh "mvn -f StoreDomainImpl/pom.xml install  -U -e deploy:deploy -DdeployAtEnd=true -P jenkins -B"
                    }
                }
            }
        }
        stage("FCC Catalog Domain Impl Build") {
            steps {
                dir('module'){
                    withMaven(mavenLocalRepo: '.repository', jdk: JDK_VERSION, maven: 'MAVEN_3.5.0', options: [artifactsPublisher(disabled: true)]) {
                        sh "mvn -f CatalogDomainImpl/pom.xml install  -U -e deploy:deploy -DdeployAtEnd=true -P jenkins -B"
                    }
                }
            }
        }
        stage("FCC Fitnesse Build") {
            steps {
                dir('module'){
                    withMaven(mavenLocalRepo: '.repository', jdk: 'OPENJDK8U191', maven: 'MAVEN_3.5.0') {
                        sh "mvn -f Fitnesse/pom.xml versions:set -DnewVersion=${PARENT_FCC_VERSION} -DgenerateBackupPoms=false -B"
                        sh "mvn -f Fitnesse/pom.xml install -B -U -e deploy:deploy -DdeployAtEnd=true"
                        script {
                            env.FITNESSE_VERSION = PARENT_FCC_VERSION
                        }
                    }
                }
            }
        }
        stage("FCC Catalog War Build") {
            steps {
                dir('module'){
                    withMaven(mavenLocalRepo: '.repository', jdk: JDK_VERSION, maven: 'MAVEN_3.5.0') {
                        sh "mvn -f CatalogWar/pom.xml install -B -U -e -P jenkins"
                        script {
                            env.FCC_VERSION = PARENT_FCC_VERSION
                            currentBuild.description = currentBuild.description + "<br/>CatalogWar=${FCC_VERSION}"
                        }
                    }
                }
            }
        }
        stage("Upload keyspaces") {
            steps {
                withCredentials([usernamePassword(credentialsId: 'artifactory_deployer', passwordVariable: 'dpass', usernameVariable: 'duser')]) {
                    script {
                        sh """virtualenv -p python2.7 ${WORKSPACE}/venv
                          source ${WORKSPACE}/venv/bin/activate
                          source ./venv/bin/activate
                          pip install pyyaml
                          pip install requests
                          pip install argparse
                          pip install Cheetah
                          pip install lxml
                          python scripts/run_cassandra.py deploy -t release --predefined-version ${FCC_VERSION} -R ${release} -c google --cqls-source-dir module/Runner/cassandra
                          python scripts/run_cassandra.py deploy -t release --predefined-version ${FCC_VERSION} -R ${release} -c google --cqls-source-dir module/Runner/cassandra --cqls-remote artifactory --artifactory-user ${duser} --artifactory-password ${dpass}
                          deactivate"""
                    }
                }
                dir('module/Runner/cassandra') {
                    script {
                        sh "tar -cvzf ${FCC_VERSION}.tar.gz *"
                        rtLib.upload(pattern: "${FCC_VERSION}.tar.gz", target: "cassandra-fcc-cqls/releases/${FCC_VERSION}/")
                    }
                }
            }
        }
        stage("Prepare Tests/ Environment") {
            steps {
                script {
                    if (!env.DOCKER_STAGE_REGISTRY) env.DOCKER_STAGE_REGISTRY = "ci-artifacts.code.com:6670/cap"
                    env.APP_DOCKER_IMAGE_STAGE = "${DOCKER_STAGE_REGISTRY}/fcc:${FCC_VERSION}"
                    echo "Application docker Image is ${APP_DOCKER_IMAGE_STAGE}"

                    if (!env.DOCKER_PROD_REGISTRY) env.DOCKER_PROD_REGISTRY = "ci-artifacts.code.com/prod"
                    env.APP_DOCKER_IMAGE = "${DOCKER_PROD_REGISTRY}/fcc:${FCC_VERSION}"

                    env.FCC_WAR_URL = "${BUILD_URL}artifact/com/test/platform/catalog.war/${FCC_VERSION}/catalog.war-${FCC_VERSION}.war"
                    env.FCC_WEB_CONFIG_URL = "${BUILD_URL}artifact/com/test/platform/catalog.war.config/${FCC_VERSION}/catalog.war.config-${FCC_VERSION}.tar"
                    echo "FCC version :  ${FCC_VERSION}"
                    currentBuild.description += "<br/>FCC version :  ${FCC_VERSION}"
                    env.FITNESSE_URL = "${BUILD_URL}artifact/com/test/platform/fcc.functional-tests/${FITNESSE_VERSION}/fcc.functional-tests-${FITNESSE_VERSION}-zip.zip"
                    echo "Fitnesse version :  ${FITNESSE_VERSION}"
                    currentBuild.description += "<br/>Fitnesse version :  ${FITNESSE_VERSION}"
                    env.ETL_VERSION = adbHelper.findArtifact(['artifactName': 'FCC-ETL', 'release': "${release}"]).version
                    env.ETL_WAR_URL = adbHelper.findArtifact(['artifactName': 'FCC-ETL', 'release': "${release}", 'version': "${ETL_VERSION}"]).location
                    env.ETL_WEB_CONFIG_URL = adbHelper.findArtifact(['artifactName': 'FCC-ETLConfig', 'release': "${release}", 'version': "${ETL_VERSION}"]).location
                    env.previous_release = adbHelper.previousRelease(['release': "${release}"])
                    env.FCC_PREV_VERSION = adbHelper.findArtifact(['artifactName': 'FCC', 'release': "${previous_release}"]).version
                    env.FCC_PREV_WAR_URL = adbHelper.findArtifact(['artifactName': 'FCC', 'release': "${previous_release}", 'version': "${FCC_PREV_VERSION}"]).location
                    env.FCC_PREV_WEB_CONFIG_URL = adbHelper.findArtifact(['artifactName': 'FCCConfig', 'release': "${previous_release}", 'version': "${FCC_PREV_VERSION}"]).location
                    env.KEYSPACES_VERSION = env.FCC_VERSION
                    env.CQLS_TYPE = 'releases'
                    env.SM_VERSION = adbHelper.findArtifact(['artifactName': 'SmartMonkey', 'release': "${release}"]).version
                    dir('soap-tests') {
                        def pom = readMavenPom file: 'SoapTestRunner/pom.xml'
                        env.SOAP_VERSION = pom.version
                        echo "SoapTests version :  ${SOAP_VERSION}"
                        currentBuild.description += "<br/>SoapTests version :  ${SOAP_VERSION}"
                    }
                    //Adding parameters for Workflow and Zeus job
                    downstream_params = [
                            string(name: 'FCC_VERSION', value: "${FCC_VERSION}"),
                            string(name: 'FCC_WAR_URL', value: "${FCC_WAR_URL}"),
                            string(name: 'FCC_WEB_CONFIG_URL', value: "${FCC_WEB_CONFIG_URL}"),
                            string(name: 'FITNESSE_VERSION', value: "${FITNESSE_VERSION}"),
                            string(name: 'FITNESSE_URL', value: "${FITNESSE_URL}"),
                            string(name: 'release', value: "${release}"),
                            //For SoapUI tests
                            string(name: 'gitlabSourceBranch', value: "${gitlabSourceBranch}")
                    ]
                }
            }
        }
        stage('Docker Build') {
            steps {
                script {
                    BUILD_DIR = "${WORKSPACE}/build-docker-${BUILD_NUMBER}"
                    dir('module') {
                        sh """
                            mkdir -p ${BUILD_DIR}
                            cp CatalogWar/catalog.war/target/catalog.war-${FCC_VERSION}.war ${BUILD_DIR}/catalog.war-${FCC_VERSION}.war
                            mkdir properties
                            tar -xvf CatalogWar/catalog.war.config/target/catalog.war.config-${FCC_VERSION}.tar -C properties/ --strip=2
                            cp properties/application.properties ${BUILD_DIR}/application.properties
                            cp -rf Runner/Dockerfile Runner/entrypoint.sh Runner/jmx-exporter Runner/wildfly ${BUILD_DIR}/
                        """
                    }
                    sd = seaDog k8s_token_id: 'devops_platform_c1', certificateId: 'devops_platfrom_c1_cert'
                    sd.kanikoBuild context: "${BUILD_DIR}", args: [
                        "--destination=${APP_DOCKER_IMAGE_STAGE}",
                        "--build-arg=WARFILE=catalog.war-${FCC_VERSION}.war",
                        "--build-arg=ENVIRONMENT_PROPERTIES_PATH=application.properties"
                    ]
                }
            }
        }
        stage("Build liquibase") {
            when {
                expression { CHANGED_FILES.any { it.contains("Runner/sitedb/") } }
            }
            steps {
                script {
                    BUILD_SITEDB_DIR = "${WORKSPACE}/build-docker-sitedb-${BUILD_NUMBER}"
                    dir('module/Runner/sitedb') {
                        withMaven(mavenLocalRepo: '.repository', jdk: 'OPENJDK8U191', maven: 'MAVEN_3.5.0', options: [artifactsPublisher(disabled: true)]) {
                            sh "mvn clean package -e -B"
                            env.sitedb_fcc_tag = "${FCC_VERSION}-${BUILD_NUMBER}"
                        }
                        sh """
                            mkdir -p ${BUILD_SITEDB_DIR}
                            cp -rf Dockerfile entrypoint.sh liquibase target ${BUILD_SITEDB_DIR}/
                        """
                    }
                    sd = seaDog k8s_token_id: 'devops_platform_c1', certificateId: 'devops_platfrom_c1_cert'
                    sd.kanikoBuild context: "${BUILD_SITEDB_DIR}", args: [
                            "--destination=${DOCKER_STAGE_REGISTRY}/sitedb-fcc:${sitedb_fcc_tag}"
                    ]
                }
            }
        }
        stage("Tests") {
            when {
                expression {  CHANGED_FILES.any { !it.contains('CatalogWar/catalog.war.config/src/main/resources/overrides') } } // Tests are not performed if the changes were made only in property files
            }
            parallel {
                stage('Publishing BCOM MYSQL') {
                    agent { label 'k8s-n1-s2' }
                    tools { jdk FITNESSE_JDK_VERSION }
                    steps {
                        script {
                            runTests.runFitnesseTests('ci-definitions/FCC/Deploy_FCC_Publishing_BCOM_mysql_GCP', 'Publishing_BCOM_mysql_GCP', env.FITNESSE_URL, [k8s_token_id: 'devops_platform_c1', k8s_certificate_id: 'devops_platfrom_c1_cert', 'remote': true, 'fitnesse_offering_name': 'fcc_fitnesse_ip', 'k8s_apps': ['fcc']])
                        }
                    }
                }
                stage('Publishing MCOM MYSQL') {
                    agent { label 'k8s-n1-s2' }
                    tools { jdk FITNESSE_JDK_VERSION }
                    steps {
                        script {
                            runTests.runFitnesseTests('ci-definitions/FCC/Deploy_FCC_Publishing_MCOM_mysql_GCP', 'Publishing_MCOM_mysql_GCP', env.FITNESSE_URL, [k8s_token_id: 'devops_platform_c1', k8s_certificate_id: 'devops_platfrom_c1_cert', 'remote': true, 'fitnesse_offering_name': 'fcc_fitnesse_ip', 'k8s_apps': ['fcc']])
                        }
                    }
                }
                stage('Publishing MCOM mysql redis') {
                    agent { label 'k8s-n1-s2' }
                    tools { jdk FITNESSE_JDK_VERSION }
                    steps {
                        script {
                            runTests.runFitnesseTests('ci-definitions/FCC/Deploy_FCC_Publishing_MCOM_mysql_redis_GCP', 'Publishing_MCOM_mysql_redis_GCP', env.FITNESSE_URL, [k8s_token_id: 'devops_platform_c1', k8s_certificate_id: 'devops_platfrom_c1_cert', 'remote': true, 'fitnesse_offering_name': 'fcc_fitnesse_ip', 'upload': true, 'k8s_apps': ['fcc']])
                        }
                    }
                }
                stage('Publishing BCOM mysql redis') {
                    agent { label 'k8s-n1-s2' }
                    tools { jdk FITNESSE_JDK_VERSION }
                    steps {
                        script {
                            runTests.runFitnesseTests('ci-definitions/FCC/Deploy_FCC_Publishing_BCOM_mysql_redis_GCP', 'Publishing_BCOM_mysql_redis_GCP', env.FITNESSE_URL, [k8s_token_id: 'devops_platform_c1', k8s_certificate_id: 'devops_platfrom_c1_cert', 'remote': true, 'fitnesse_offering_name': 'fcc_fitnesse_ip', 'upload': true, 'k8s_apps': ['fcc']])
                        }
                    }
                }
                stage('Publishing MCOM mysql redis PAG') {
                    agent { label 'k8s-n1-s2' }
                    tools { jdk FITNESSE_JDK_VERSION }
                    steps {
                        script {
                            runTests.runFitnesseTests('ci-definitions/FCC/Deploy_FCC_Publishing_MCOM_mysql_redis_PAG_GCP', 'Publishing_MCOM_mysql_redis_PAG_GCP', env.FITNESSE_URL, [k8s_token_id: 'devops_platform_c1', k8s_certificate_id: 'devops_platfrom_c1_cert', 'remote': true, 'fitnesse_offering_name': 'fcc_fitnesse_ip', 'upload': true, 'k8s_apps': ['fcc']])
                        }
                    }
                }
                stage('Publishing BCOM mysql PAG') {
                    agent { label 'k8s-n1-s2' }
                    tools { jdk FITNESSE_JDK_VERSION }
                    steps {
                        script {
                            runTests.runFitnesseTests('ci-definitions/FCC/Deploy_FCC_Publishing_BCOM_mysql_redis_PAG_GCP', 'Publishing_BCOM_mysql_redis_PAG_GCP', env.FITNESSE_URL, [k8s_token_id: 'devops_platform_c1', k8s_certificate_id: 'devops_platfrom_c1_cert', 'remote': true, 'fitnesse_offering_name': 'fcc_fitnesse_ip', 'upload': true, 'k8s_apps': ['fcc']])
                        }
                    }
                }
                stage('FUNC BCOM MYSQL') {
                    agent { label 'k8s-n1-s2' }
                    tools { jdk FITNESSE_JDK_VERSION }
                    steps {
                        script {
                            runTests.runFitnesseTests('ci-definitions/FCC/Deploy_FCC_FUNC_BCOM_mysql_GCP', 'FUNC_BCOM_mysql_GCP', env.FITNESSE_URL, [k8s_token_id: 'devops_platform_c1', k8s_certificate_id: 'devops_platfrom_c1_cert', 'seadog_ports': [6555, 8182, 9094, 9095, 9097, 9098, 9099, 12345, 18100],'upload': true, 'host_offering_names': ['fcc_service_ip', 'fcc_maintenance_ip'], 'fitnesse_path': '/tmp/fitnesse', 'k8s_apps': ['fcc']])
                        }
                    }
                }
                stage('FUNC MCOM MYSQL') {
                    agent { label 'k8s-n1-s2' }
                    tools { jdk FITNESSE_JDK_VERSION }
                    steps {
                        script {
                            runTests.runFitnesseTests('ci-definitions/FCC/Deploy_FCC_FUNC_MCOM_mysql_GCP', 'FUNC_MCOM_mysql_GCP', env.FITNESSE_URL, [k8s_token_id: 'devops_platform_c1', k8s_certificate_id: 'devops_platfrom_c1_cert', 'seadog_ports': [6555, 8182, 9094, 9095, 9097, 9098, 9099, 12345, 18100],'upload': true, 'host_offering_names': ['fcc_service_ip', 'fcc_maintenance_ip'], 'fitnesse_path': '/tmp/fitnesse', 'k8s_apps': ['fcc']])
                        }
                    }
                }
                stage('CatalogServiceNew BCOM MYSQL') {
                    agent { label 'k8s-n1-s2' }
                    tools { jdk FITNESSE_JDK_VERSION }
                    steps {
                        script {
                            runTests.runFitnesseTests('ci-definitions/FCC/Deploy_FCC_CatalogServiceNew_BCOM_MYSQL_GCP', 'CatalogServiceNew_BCOM_MYSQL_GCP', env.FITNESSE_URL, [k8s_token_id: 'devops_platform_c1', k8s_certificate_id: 'devops_platfrom_c1_cert', 'seadog_ports': [5555, 8182, 9094, 9095, 9097, 9098, 9099, 12345, 18100],'upload': true, 'host_offering_names': ['fcc_service_ip', 'fcc_maintenance_ip'], 'fitnesse_path': '/tmp/fitnesse', 'k8s_apps': ['fcc']])
                        }
                    }
                }
                stage('CatalogServiceNew MCOM MYSQL') {
                    agent { label 'k8s-n1-s2' }
                    tools { jdk FITNESSE_JDK_VERSION }
                    steps {
                        script {
                            runTests.runFitnesseTests('ci-definitions/FCC/Deploy_FCC_CatalogServiceNew_MCOM_MYSQL_GCP', 'CatalogServiceNew_MCOM_MYSQL_GCP', env.FITNESSE_URL, [k8s_token_id: 'devops_platform_c1', k8s_certificate_id: 'devops_platfrom_c1_cert', 'seadog_ports': [5555, 8182, 9094, 9095, 9097, 9098, 9099, 12345, 18100],'upload': true, 'host_offering_names': ['fcc_service_ip', 'fcc_maintenance_ip'], 'fitnesse_path': '/tmp/fitnesse', 'k8s_apps': ['fcc']])
                        }
                    }
                }
                stage('ColorwayPricing BCOM MYSQL') {
                    agent { label 'k8s-n1-s2' }
                    tools { jdk FITNESSE_JDK_VERSION }
                    steps {
                        script {
                            runTests.runFitnesseTests('ci-definitions/FCC/Deploy_FCC_ColorwayPricing_BCOM_MYSQL_GCP', 'ColorwayPricing_BCOM_MYSQL_GCP', env.FITNESSE_URL, [k8s_token_id: 'devops_platform_c1', k8s_certificate_id: 'devops_platfrom_c1_cert', 'seadog_ports': [6555, 8182, 9094, 9095, 9097, 9098, 9099, 12345, 18100], 'upload': true, 'host_offering_names': ['fcc_service_ip', 'fcc_maintenance_ip'], 'fitnesse_path': '/tmp/fitnesse', 'k8s_apps': ['fcc']])
                        }
                    }
                }
                stage('ColorwayPricing MCOM MYSQL') {
                    agent { label 'k8s-n1-s2' }
                    tools { jdk FITNESSE_JDK_VERSION }
                    steps {
                        script {
                            runTests.runFitnesseTests('ci-definitions/FCC/Deploy_FCC_ColorwayPricing_MCOM_MYSQL_GCP', 'ColorwayPricing_MCOM_MYSQL_GCP', env.FITNESSE_URL, [k8s_token_id: 'devops_platform_c1', k8s_certificate_id: 'devops_platfrom_c1_cert', 'seadog_ports': [6555, 8182, 9094, 9095, 9097, 9098, 9099, 12345, 18100], 'upload': true, 'host_offering_names': ['fcc_service_ip', 'fcc_maintenance_ip'], 'fitnesse_path': '/tmp/fitnesse', 'k8s_apps': ['fcc']])
                        }
                    }
                }
                stage('StoreRestService BCOM MYSQL') {
                    agent { label 'k8s-n1-s2' }
                    tools { jdk FITNESSE_JDK_VERSION }
                    steps {
                        script {
                            runTests.runSoapTests('ci-definitions/FCC/Deploy_FCC_StoreRestService_BCOM_MYSQL_GCP', 'StoreRestService_BCOM_MYSQL_GCP', [k8s_token_id: 'devops_platform_c1', k8s_certificate_id: 'devops_platfrom_c1_cert', 'seadog_ports': [8180, 8182, 9091, 12345], 'host_offering_names': ['fcc_service_ip', 'fcc_maintenance_ip'], 'config_file': 'storeRestService.properties', 'test_name': 'StoreRestService', 'mvn_cfg': ['mavenOpts': '-XX:MaxPermSize=256m', 'mvnProfiles': 'store-bcom'], 'k8s_apps': ['fcc']])
                        }
                    }
                }
                stage('StoreRestService MCOM MYSQL') {
                    agent { label 'k8s-n1-s2' }
                    tools { jdk FITNESSE_JDK_VERSION }
                    steps {
                        script {
                            runTests.runSoapTests('ci-definitions/FCC/Deploy_FCC_StoreRestService_MCOM_MYSQL_GCP', 'StoreRestService_MCOM_MYSQL_GCP', [k8s_token_id: 'devops_platform_c1', k8s_certificate_id: 'devops_platfrom_c1_cert', 'seadog_ports': [8180, 8182, 9091, 12345], 'host_offering_names': ['fcc_service_ip', 'fcc_maintenance_ip'], 'config_file': 'storeRestService.properties', 'test_name': 'StoreRestService', 'mvn_cfg': ['mavenOpts': '-XX:MaxPermSize=256m', 'mvnProfiles': 'store'], 'k8s_apps': ['fcc']])
                        }
                    }
                }
                stage('ReviewRestService BCOM MYSQL') {
                    agent { label 'k8s-n1-s2' }
                    tools { jdk FITNESSE_JDK_VERSION }
                    steps {
                        script {
                            runTests.runSoapTests('ci-definitions/FCC/Deploy_FCC_ReviewRestService_BCOM_mysql_GCP', 'ReviewRestService_BCOM_mysql_GCP', [k8s_token_id: 'devops_platform_c1', k8s_certificate_id: 'devops_platfrom_c1_cert', 'seadog_ports': [8180, 8181, 12345], 'host_offering_names': ['fcc_service_ip', 'fcc_maintenance_ip'], 'config_file': 'reviewRestService.properties', 'test_name': 'ReviewRestService', 'mvn_cfg': ['mavenOpts': '-XX:PermSize=256m -XX:MaxPermSize=512m', 'mvnProfiles': 'reviewBcom,testSnapshots,withOpencastRepo'], 'k8s_apps': ['fcc']])
                        }
                    }
                }
                stage('ReviewRestService MCOM MYSQL') {
                    agent { label 'k8s-n1-s2' }
                    tools { jdk FITNESSE_JDK_VERSION }
                    steps {
                        script {
                            runTests.runSoapTests('ci-definitions/FCC/Deploy_FCC_ReviewRestService_MCOM_mysql_GCP', 'ReviewRestService_MCOM_mysql_GCP', [k8s_token_id: 'devops_platform_c1', k8s_certificate_id: 'devops_platfrom_c1_cert', 'seadog_ports': [8180, 8181, 12345], 'host_offering_names': ['fcc_service_ip', 'fcc_maintenance_ip'], 'config_file': 'reviewRestService.properties', 'test_name': 'ReviewRestService', 'mvn_cfg': ['mavenOpts': '-XX:PermSize=256m -XX:MaxPermSize=512m', 'mvnProfiles': 'review,testSnapshots,withOpencastRepo'], 'k8s_apps': ['fcc']])
                        }
                    }
                }
                stage('Publishing ReviewRestService MYSQL') {
                    agent { label 'k8s-n1-s2' }
                    tools { jdk FITNESSE_JDK_VERSION }
                    steps {
                        script {
                            runTests.runSoapTests('ci-definitions/FCC/Deploy_FCC_Publishing_ReviewRestService_MCOM_mysql_GCP', 'Publishing_ReviewRestService_MCOM_mysql_GCP', [k8s_token_id: 'devops_platform_c1', k8s_certificate_id: 'devops_platfrom_c1_cert', 'seadog_ports': [37513, 18100, 12345], 'host_offering_names': ['fcc_service_ip', 'fcc_maintenance_ip'], 'config_file': 'reviewRestService.properties', 'test_name': 'ReviewRestService', 'mvn_cfg': ['mavenOpts': '-XX:PermSize=256m -XX:MaxPermSize=512m', 'mvnProfiles': 'review,testSnapshots,review-publishing'], 'k8s_apps': ['fcc']])
                        }
                    }
                }
                //commented out as release branching was replaced by tags so previous version can’t be fetched from the previous release
                /*
                stage('Backward Compatibility MYSQL') {
                    agent { label 'k8s-n1-s2' }
                    steps {
                        script {
                            runBackwardCompatibility.runBackwardCompatibility('Backward_Compatibility_mysql_GCP', ['Backward_Compatibility_current_mysql_GCP': ['template_path': 'ci-definitions/FCC/Deploy_FCC_Backward_Compatibility_current_mysql_GCP', 'seadog_ports': [8180, 12345, 18100]], 'Backward_Compatibility_previous_mysql_GCP': ['template_path': 'ci-definitions/FCC/Deploy_FCC_Backward_Compatibility_previous_mysql_GCP', 'seadog_ports': [8180, 12345, 18100]]], env.FITNESSE_URL, ['fcc_offering_name': 'fcc_maintenance_gcp_dns_name', 'discovery_offering_name': 'discovery_maintenance_url', k8s_token_id: 'devops_platform_c1', k8s_certificate_id: 'devops_platfrom_c1_cert', 'k8s_apps': ['fcc']])
                        }
                    }
                } */
                stage('MYSQL EDD Flow') {
                    when {
                        expression { CHANGED_FILES.any { it.contains("Runner/sitedb/") } }
                    }
                    steps {
                        script {
                            zeusCli.create zeusTemplate: 'ci-definitions/FCC/FCC_MYSQL_EDD.yaml', envGroup: 'devops', envName: "fcc-master-deploy-mysql-${BUILD_NUMBER}"
                        }
                    }
                }
            }
        }
        stage('Docker sitedb Publish') {
            when {
                expression { CHANGED_FILES.any { it.contains("Runner/sitedb/") } && currentBuild.currentResult == 'SUCCESS' }
            }
            steps {
                script {
                    rtLib.promoteDockerImage apiKeyCred: 'artifactory_deployer_token',
                        oldRepoKey: 'test-docker-review-registry',
                        oldImage: 'cap/sitedb-fcc',
                        oldTag: "${sitedb_fcc_tag}",
                        newRepoKey: 'test-docker-registry',
                        newImage: 'prod/sitedb-fcc',
                        newTag: "${FCC_VERSION}",
                        copy: false
                }
            }
        }
        stage('Docker Publish') {
            when {
                expression { currentBuild.currentResult == 'SUCCESS' }
            }
            steps {
                script {
                    rtLib.promoteDockerImage apiKeyCred: 'artifactory_deployer_token',
                                             oldRepoKey: 'test-docker-review-registry',
                                             oldImage: 'cap/fcc',
                                             oldTag: "${FCC_VERSION}",
                                             newRepoKey: 'test-docker-registry',
                                             newImage: 'prod/fcc',
                                             newTag: "${FCC_VERSION}",
                                             copy: false
                }
            }
        }
        stage('Push to Artifactory') {
            when {
                expression { currentBuild.currentResult == 'SUCCESS' }
            }
            steps {
                dir("module") {
                    script {
                        def app_builder = rtLib.buildMaven([
                            java: JDK_VERSION,
                            maven: 'MAVEN_3.5.0',
                            goals: 'clean install -B -U -e -P jenkins -Dmaven.repo.local=.repository -DskipTests=true',
                            pom: 'CatalogWar/pom.xml',
                            maven_settings_id: 'DevopsMavenSettings_main',
                            publish: true
                        ])
                        // Commented due to artifactory plugin upgrading. In the new version of plugin `getArtifact` method is not exist
                        // env.FCC_ARTIFACT_WAR_URL = app_builder.getArtifact('catalog.war-%v.war')
                        // env.FCC_ARTIFACT_CONFIG_URL = app_builder.getArtifact('catalog.war.config-%v.tar')
                    }
                }
            }
        }
        stage('Push sitedb to ADB') {
            when {
                expression { CHANGED_FILES.any { it.contains("Runner/sitedb/") } && currentBuild.currentResult == 'SUCCESS' }
            }
            steps {
                script {
                    reportArtifact release: "${release}",
                        artifactName: 'sitedb-fcc',
                        artifactVersion: "${FCC_VERSION}",
                        groupType: "CAP",
                        artifactLocation: "ci-artifacts.code.com/prod/sitedb-fcc:${FCC_VERSION}",
                        uploadBuildUrl: "${BUILD_URL}"
                }
            }
        }
        stage('Push to ADB/Repo') {
            when {
                expression { currentBuild.currentResult == 'SUCCESS' }
            }
            steps {
                sshagent(['git_gitlab.code.com']) {
                    dir('module') {
                        sh "git tag -f last_green_${release} ${gitlabAfter}"
                        sh "git tag -f v${PARENT_FCC_VERSION}"
                        sh "git push -f --tags"
                    }
                }
                script {
                    reportArtifact release: "${release}",
                            artifactName: 'FCC',
                            artifactVersion: "${FCC_VERSION}",
                            groupType: "CAP",
                            artifactLocation: "http://ci-artifacts.code.com/test-release-local/com/test/platform/catalog.war/${FCC_VERSION}/catalog.war-${FCC_VERSION}.war",
                            uploadBuildUrl: "${BUILD_URL}"

                    reportArtifact release: "${release}",
                            artifactName: 'FCCConfig',
                            artifactVersion: "${FCC_VERSION}",
                            groupType: "CAP",
                            artifactLocation: "http://ci-artifacts.code.com/test-release-local/com/test/platform/catalog.war.config/${FCC_VERSION}/catalog.war.config-${FCC_VERSION}.tar",
                            uploadBuildUrl: "${BUILD_URL}"
                    reportArtifact release: "${release}",
                            artifactName: 'FCCFitnesse',
                            artifactVersion: "${FITNESSE_VERSION}",
                            groupType: "CAP",
                            artifactLocation: "http://ci-artifacts.code.com/test-release-local/com/test/platform/fcc.functional-tests/${FITNESSE_VERSION}/fcc.functional-tests-${FITNESSE_VERSION}-zip.zip",
                            uploadBuildUrl: "${BUILD_URL}"
                }
            }
        }
    }
    post{
        failure {
            updateGitlabCommitStatus name: 'Jenkins', state: 'failed'
        }
        unstable {
            updateGitlabCommitStatus name: 'Jenkins', state: 'failed'
        }
        success {
            updateGitlabCommitStatus name: 'Jenkins', state: 'success'
        }
        always {
            script {
                fitnesse.publish_reports(["Publishing_MCOM_mysql_GCP","Publishing_BCOM_mysql_GCP","Publishing_MCOM_mysql_redis_GCP","Publishing_BCOM_mysql_redis_GCP","Publishing_MCOM_mysql_redis_PAG_GCP","Publishing_BCOM_mysql_redis_PAG_GCP","FUNC_MCOM_mysql_GCP","FUNC_BCOM_mysql_GCP","CatalogServiceNew_MCOM_MYSQL_GCP","CatalogServiceNew_BCOM_MYSQL_GCP","ColorwayPricing_MCOM_MYSQL_GCP","ColorwayPricing_BCOM_MYSQL_GCP","Backward_Compatibility_current_mysql_GCP","Backward_Compatibility_previous_mysql_GCP"])
                //archiveArtifacts artifacts: '*.log'
            }
        }
    }
}