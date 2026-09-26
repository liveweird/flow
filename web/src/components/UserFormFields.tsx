import { Checkbox, Select, Stack, TextInput } from "@mantine/core";
import { type UseFormReturnType } from "@mantine/form";
import { useTranslation } from "react-i18next";
import { NATIVE_LANGUAGE_NAMES, SUPPORTED_LANGUAGES } from "../i18n";
import { MAX_EMAIL_LENGTH, MAX_USER_NAME_LENGTH, type UserFormValues } from "../utils/userForm";

/** The field block shared by the create and edit user pages (which own submit/error handling). */
export default function UserFormFields({ form }: { form: UseFormReturnType<UserFormValues> }) {
  const { t } = useTranslation();
  return (
    <Stack gap="sm">
      <TextInput
        label={t("common.field.name")}
        autoFocus
        required
        maxLength={MAX_USER_NAME_LENGTH}
        {...form.getInputProps("name")}
      />
      <TextInput
        label={t("common.field.email")}
        required
        maxLength={MAX_EMAIL_LENGTH}
        {...form.getInputProps("email")}
      />
      <Select
        label={t("common.language.label")}
        description={t("users.languageHint")}
        data={SUPPORTED_LANGUAGES.map((lng) => ({ value: lng, label: NATIVE_LANGUAGE_NAMES[lng] }))}
        allowDeselect={false}
        {...form.getInputProps("language")}
      />
      <Checkbox
        label={t("users.adminCheckbox")}
        description={t("users.adminCheckboxHint")}
        {...form.getInputProps("admin", { type: "checkbox" })}
      />
    </Stack>
  );
}
