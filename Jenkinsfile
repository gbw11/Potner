pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    parameters {
        choice(
            name: 'TARGET',
            choices: ['validate', 'deploy-pi'],
            description: 'validate=의존성/문법 검사만, deploy-pi=라즈베리파이 배포'
        )
        string(
            name: 'PI_HOST',
            defaultValue: 'raspberrypi',
            description: 'SSH Host (예: raspberrypi 또는 192.168.30.91)'
        )
        string(
            name: 'PI_USER',
            defaultValue: 'e104',
            description: 'SSH 사용자'
        )
        string(
            name: 'PI_DIR',
            defaultValue: '~/S15P11E104',
            description: 'Pi 배포 경로'
        )
    }

    environment {
        PYTHONUNBUFFERED = '1'
        VENV = "${WORKSPACE}/.venv-ci"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Setup Python') {
            steps {
                sh '''
                    set -e
                    python3 -m venv "${VENV}"
                    . "${VENV}/bin/activate"
                    python -m pip install --upgrade pip
                    pip install -r requirements.txt
                '''
            }
        }

        stage('Validate') {
            steps {
                sh '''
                    set -e
                    . "${VENV}/bin/activate"
                    python -m compileall -q \
                        main.py cli scripts src
                    python - <<'PY'
from pathlib import Path
import yaml

for name in ("config/default.yaml", "config/raspberry_pi.yaml"):
    path = Path(name)
    assert path.exists(), f"missing {name}"
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    assert isinstance(data, dict), f"invalid yaml: {name}"
    print(f"OK {name} keys={list(data)[:8]}")
print("validate passed")
PY
                '''
            }
        }

        stage('Deploy to Raspberry Pi') {
            when {
                expression { return params.TARGET == 'deploy-pi' }
            }
            steps {
                sshagent(credentials: ['raspberrypi-ssh']) {
                    sh '''
                        set -e
                        RSYNC_SSH="ssh -o StrictHostKeyChecking=accept-new"
                        rsync -az --delete \
                          --exclude '.venv' \
                          --exclude '.venv-ci' \
                          --exclude '__pycache__' \
                          --exclude 'data/' \
                          --exclude 'captures/' \
                          --exclude '.git' \
                          --exclude '.env' \
                          --exclude '.claude' \
                          --exclude 'sensor-collector/' \
                          -e "${RSYNC_SSH}" \
                          ./ "${PI_USER}@${PI_HOST}:${PI_DIR}/"

                        ${RSYNC_SSH} "${PI_USER}@${PI_HOST}" bash -s <<EOF
set -e
cd ${PI_DIR}
if [ ! -d .venv ]; then
  python3 -m venv --system-site-packages .venv
fi
. .venv/bin/activate
pip install -r requirements.txt
python -m compileall -q main.py cli scripts src
echo "deploy ok on \$(hostname)"
EOF
                    '''
                }
            }
        }
    }

    post {
        success {
            echo "Pipeline succeeded (TARGET=${params.TARGET})"
        }
        failure {
            echo "Pipeline failed (TARGET=${params.TARGET})"
        }
        always {
            cleanWs(deleteDirs: true, notFailBuild: true)
        }
    }
}
