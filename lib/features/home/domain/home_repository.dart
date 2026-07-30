import 'package:potner_app/features/home/domain/home_dashboard.dart';

abstract interface class HomeRepository {
  Future<List<HomePlant>> getPlants();

  Future<HomeDashboard> getDashboard(HomePlant plant);

  Future<int> getUnreadAlertCount();
}
