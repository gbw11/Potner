from setuptools import find_packages, setup


package_name = "potner_exploration"

setup(
    name=package_name,
    version="0.1.0",
    packages=find_packages(exclude=["test"]),
    data_files=[
        ("share/ament_index/resource_index/packages", ["resource/" + package_name]),
        ("share/" + package_name, ["package.xml"]),
    ],
    install_requires=["setuptools"],
    zip_safe=True,
    maintainer="E104",
    maintainer_email="notbad1700@gmail.com",
    description="OccupancyGrid frontier 기반 Potner 자율 탐색과 지도 저장",
    license="MIT",
    entry_points={
        "console_scripts": [
            "exploration = potner_exploration.exploration_node:main",
        ],
    },
)
