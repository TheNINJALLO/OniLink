#include <onibridge/operations.hpp>

#include <stdexcept>

namespace onistone::onibridge {

MigrationPlan LegacyDataMigrator::plan(
    const std::filesystem::path& source,
    const std::filesystem::path& destination) {
    const auto normalized_source = std::filesystem::weakly_canonical(source);
    const auto normalized_destination = std::filesystem::weakly_canonical(destination);
    const bool source_exists = std::filesystem::exists(normalized_source);
    const bool destination_exists = std::filesystem::exists(normalized_destination);
    return {normalized_source, normalized_destination, source_exists, destination_exists,
            source_exists && !destination_exists && normalized_source != normalized_destination};
}

void LegacyDataMigrator::apply(const MigrationPlan& plan, bool explicit_confirmation) {
    if (!explicit_confirmation || !plan.safe_to_apply) throw std::runtime_error("migration is not explicitly confirmed or safe");
    std::filesystem::create_directories(plan.destination.parent_path());
    std::filesystem::copy(plan.source, plan.destination, std::filesystem::copy_options::recursive);
}

} // namespace onistone::onibridge

