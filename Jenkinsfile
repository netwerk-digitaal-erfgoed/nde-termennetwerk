properties([pipelineTriggers([githubPush()])])
podTemplate(label: 'jenkins-slave', containers: [
    containerTemplate(name: 'git', image: 'alpine/git', ttyEnabled: true, command: 'cat'),
    containerTemplate(name: 'docker', image: 'docker', command: 'cat', ttyEnabled: true),
    containerTemplate(name: 'kubectl', image: 'lachlanevenson/k8s-kubectl', command: 'cat', ttyEnabled: true)
  ],
  volumes: [
    hostPathVolume(mountPath: '/var/run/docker.sock', hostPath: '/var/run/docker.sock'),
  ]
  ) {
    node('jenkins-slave') {
        def secrets = [
            [path: 'secret/docker', engineVersion: 1, secretValues: [
                [envVar: 'DOCKER_PSW', vaultKey: 'DOCKER_PSW'],
                [envVar: 'DOCKER_USR', vaultKey: 'DOCKER_USR']]]
        ]
        def configuration = [vaultUrl: 'http://vault-helm.default.svc.cluster.local:8200',
                            vaultCredentialId: 'c64b32ee-4891-44ca-b151-42caea9856bc', engineVersion: 1]

        stage('Clone repository') {
            container('git') {
                sh 'git clone -b master  https://github.com/samleeflang/nde-termennetwerk'
            }
        }

        stage('Docker Build & Push') {
            container('docker') {
                dir('nde-termennetwerk/') {
                    withVault([configuration: configuration, vaultSecrets: secrets]) {
                        sh 'docker login harbor.51-105-200-91.nip.io/nde -u $DOCKER_USR -p $DOCKER_PSW'
                        sh 'docker build . -t harbor.51-105-200-91.nip.io/nde/nde-termennetwerk'
                        sh 'docker push harbor.51-105-200-91.nip.io/nde/nde-termennetwerk'
                    }
                }
            }
        }

        stage('Automatical deploy to kubernetes') {
            container('kubectl') {
                dir('nde-termennetwerk/') {
                    sh 'kubectl apply -f k8s'
                }
            }
        }
    }
}
