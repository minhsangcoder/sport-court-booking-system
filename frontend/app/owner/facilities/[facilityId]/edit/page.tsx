'use client'
import { useFacility } from '@/features/facility/facility-context'
import { FacilityForm } from '@/features/facility/facility-form'
export default function Page(){const {facility,reload}=useFacility();return <FacilityForm facility={facility} onSaved={reload}/>}
